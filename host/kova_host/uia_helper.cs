using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Automation;

namespace KovaUiaHelper
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern bool IsWindow(IntPtr hWnd);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetThreadDesktop(IntPtr hDesktop);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool CloseDesktop(IntPtr hDesktop);

        private static IntPtr _attachedDesk = IntPtr.Zero;

        private static void EnsureInputDesktop()
        {
            try
            {
                IntPtr hDesk = OpenInputDesktop(0, false, 0x01FF);
                if (hDesk != IntPtr.Zero)
                {
                    if (SetThreadDesktop(hDesk))
                    {
                        if (_attachedDesk != IntPtr.Zero) CloseDesktop(_attachedDesk);
                        _attachedDesk = hDesk;
                    }
                    else
                    {
                        CloseDesktop(hDesk);
                    }
                }
            }
            catch { }
        }

        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer
        {
            MaxJsonLength = 8 * 1024 * 1024
        };

        private static readonly HashSet<ControlType> InteractiveTypes = new HashSet<ControlType>
        {
            ControlType.Button,
            ControlType.Edit,
            ControlType.Hyperlink,
            ControlType.TabItem,
            ControlType.MenuItem,
            ControlType.CheckBox,
            ControlType.RadioButton,
            ControlType.ComboBox,
            ControlType.ListItem,
            ControlType.TreeItem,
            ControlType.SplitButton,
            ControlType.Slider,
            ControlType.Document,
            ControlType.Text
        };

        [MTAThread]
        public static void Main(string[] args)
        {
            EnsureInputDesktop();
            Console.InputEncoding = Encoding.UTF8;
            Console.OutputEncoding = Encoding.UTF8;

            bool daemon = args.Length > 0 && args[0] == "--daemon";
            if (!daemon)
            {
                long hwnd = args.Length > 0 ? long.Parse(args[0]) : 0;
                var resp = InspectWindow(hwnd, 160, 700);
                Console.WriteLine(Json.Serialize(resp));
                return;
            }

            string line;
            while ((line = Console.ReadLine()) != null)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    var req = Json.Deserialize<Dictionary<string, object>>(line);
                    string cmd = req.ContainsKey("cmd") ? Convert.ToString(req["cmd"]) : "";
                    object result;
                    if (cmd == "inspect")
                    {
                        long hwnd = req.ContainsKey("hwnd") && req["hwnd"] != null ? Convert.ToInt64(req["hwnd"]) : 0;
                        int maxNodes = req.ContainsKey("max_nodes") && req["max_nodes"] != null ? Convert.ToInt32(req["max_nodes"]) : 160;
                        int budgetMs = req.ContainsKey("budget_ms") && req["budget_ms"] != null ? Convert.ToInt32(req["budget_ms"]) : 650;
                        result = InspectWindow(hwnd, maxNodes, budgetMs);
                    }
                    else if (cmd == "action")
                    {
                        long hwnd = req.ContainsKey("hwnd") && req["hwnd"] != null ? Convert.ToInt64(req["hwnd"]) : 0;
                        string runtimeId = req.ContainsKey("runtime_id") && req["runtime_id"] != null ? Convert.ToString(req["runtime_id"]) : "";
                        string action = req.ContainsKey("action") && req["action"] != null ? Convert.ToString(req["action"]) : "invoke";
                        string value = req.ContainsKey("value") && req["value"] != null ? Convert.ToString(req["value"]) : "";
                        result = ExecuteAction(hwnd, runtimeId, action, value);
                    }
                    else if (cmd == "ping")
                    {
                        result = new Dictionary<string, object> { { "ok", true }, { "pong", true } };
                    }
                    else
                    {
                        result = new Dictionary<string, object> { { "ok", false }, { "error", "Unknown command: " + cmd } };
                    }
                    Console.WriteLine(Json.Serialize(result));
                    Console.Out.Flush();
                }
                catch (Exception ex)
                {
                    var err = new Dictionary<string, object>
                    {
                        { "ok", false },
                        { "error", ex.Message }
                    };
                    Console.WriteLine(Json.Serialize(err));
                    Console.Out.Flush();
                }
            }
        }

        private static string RuntimeIdToString(int[] rid)
        {
            if (rid == null || rid.Length == 0) return "";
            var sb = new StringBuilder();
            for (int i = 0; i < rid.Length; i++)
            {
                if (i > 0) sb.Append('.');
                sb.Append(rid[i]);
            }
            return sb.ToString();
        }

        private static string RoleName(ControlType ct)
        {
            if (ct == null) return "unknown";
            string prog = ct.ProgrammaticName ?? "";
            int idx = prog.LastIndexOf('.');
            return idx >= 0 ? prog.Substring(idx + 1) : prog;
        }

        private static Dictionary<string, object> InspectWindow(long hwndVal, int maxNodes, int budgetMs)
        {
            EnsureInputDesktop();
            var sw = Stopwatch.StartNew();
            IntPtr hwnd = hwndVal > 0 ? new IntPtr(hwndVal) : GetForegroundWindow();
            if (hwnd == IntPtr.Zero || !IsWindow(hwnd))
            {
                return new Dictionary<string, object>
                {
                    { "ok", false },
                    { "error", "No valid target window" },
                    { "elements", new List<object>() }
                };
            }

            AutomationElement root = AutomationElement.FromHandle(hwnd);
            if (root == null)
            {
                return new Dictionary<string, object>
                {
                    { "ok", false },
                    { "error", "Unable to attach UI Automation to window" },
                    { "elements", new List<object>() }
                };
            }

            Rect winRect = root.Current.BoundingRectangle;
            double wx = double.IsInfinity(winRect.X) ? 0 : winRect.X;
            double wy = double.IsInfinity(winRect.Y) ? 0 : winRect.Y;
            double ww = double.IsInfinity(winRect.Width) || winRect.Width <= 0 ? 1 : winRect.Width;
            double wh = double.IsInfinity(winRect.Height) || winRect.Height <= 0 ? 1 : winRect.Height;

            var elements = new List<Dictionary<string, object>>();
            var readable = new List<string>();
            var seenText = new HashSet<string>();

            // Breadth-first traversal so top-level toolbars, tabs, buttons, and inputs are always captured first
            var queue = new Queue<Tuple<AutomationElement, int>>();
            queue.Enqueue(Tuple.Create(root, 0));
            var walker = TreeWalker.ControlViewWalker;
            int visited = 0;
            int maxVisited = 1200;

            while (queue.Count > 0 && elements.Count < maxNodes && visited < maxVisited && sw.ElapsedMilliseconds < budgetMs)
            {
                var current = queue.Dequeue();
                AutomationElement el = current.Item1;
                int depth = current.Item2;
                visited++;

                if (depth > 0)
                {
                    try
                    {
                        var cur = el.Current;
                        if (!cur.IsOffscreen)
                        {
                            ControlType ct = cur.ControlType;
                            string role = RoleName(ct);
                            string name = (cur.Name ?? "").Trim();
                            Rect r = cur.BoundingRectangle;

                            bool validRect = !r.IsEmpty && !double.IsInfinity(r.Width) && !double.IsInfinity(r.Height) && r.Width >= 6 && r.Height >= 6;
                            if (validRect && InteractiveTypes.Contains(ct))
                            {
                                string val = "";
                                bool canInvoke = false;
                                bool canSetValue = false;
                                bool canToggle = false;
                                bool canSelect = false;

                                object pat;
                                if (el.TryGetCurrentPattern(ValuePattern.Pattern, out pat))
                                {
                                    var vp = (ValuePattern)pat;
                                    try { val = vp.Current.Value ?? ""; } catch { }
                                    try { canSetValue = !vp.Current.IsReadOnly; } catch { }
                                }
                                if (el.TryGetCurrentPattern(InvokePattern.Pattern, out pat)) canInvoke = true;
                                if (el.TryGetCurrentPattern(TogglePattern.Pattern, out pat)) canToggle = true;
                                if (el.TryGetCurrentPattern(SelectionItemPattern.Pattern, out pat)) canSelect = true;

                                bool isTextOnly = (ct == ControlType.Text || ct == ControlType.Document);
                                if (isTextOnly)
                                {
                                    string snippet = !string.IsNullOrEmpty(val) ? val : name;
                                    if (!string.IsNullOrWhiteSpace(snippet) && snippet.Length >= 2 && seenText.Add(snippet))
                                    {
                                        readable.Add(snippet.Length > 500 ? snippet.Substring(0, 500) : snippet);
                                    }
                                    // Include Document if editable (like VS Code / Notepad / chat boxes)
                                    if (ct == ControlType.Document && (canSetValue || cur.IsKeyboardFocusable))
                                    {
                                        // fall through to add as interactive edit element
                                    }
                                    else
                                    {
                                        goto EnqueueChildren;
                                    }
                                }

                                if (string.IsNullOrEmpty(name) && string.IsNullOrEmpty(val) && ct != ControlType.Edit && ct != ControlType.Document)
                                {
                                    goto EnqueueChildren;
                                }

                                var actions = new List<string>();
                                if (canInvoke) actions.Add("invoke");
                                if (canToggle) actions.Add("toggle");
                                if (canSelect) actions.Add("select");
                                if (canSetValue) actions.Add("set_value");
                                actions.Add("click");

                                string rid = "";
                                try { rid = RuntimeIdToString(el.GetRuntimeId()); } catch { }

                                elements.Add(new Dictionary<string, object>
                                {
                                    { "id", elements.Count + 1 },
                                    { "runtime_id", rid },
                                    { "role", role },
                                    { "name", name.Length > 160 ? name.Substring(0, 160) : name },
                                    { "value", val.Length > 240 ? val.Substring(0, 240) : val },
                                    { "enabled", cur.IsEnabled },
                                    { "focused", cur.HasKeyboardFocus },
                                    { "focusable", cur.IsKeyboardFocusable },
                                    { "actions", actions },
                                    { "x", (int)Math.Round(r.X) },
                                    { "y", (int)Math.Round(r.Y) },
                                    { "width", (int)Math.Round(r.Width) },
                                    { "height", (int)Math.Round(r.Height) },
                                    { "rel_x", Math.Round(Math.Max(0.0, Math.Min(1.0, (r.X - wx) / ww)), 4) },
                                    { "rel_y", Math.Round(Math.Max(0.0, Math.Min(1.0, (r.Y - wy) / wh)), 4) },
                                    { "rel_w", Math.Round(Math.Max(0.0, Math.Min(1.0, r.Width / ww)), 4) },
                                    { "rel_h", Math.Round(Math.Max(0.0, Math.Min(1.0, r.Height / wh)), 4) }
                                });
                            }
                        }
                    }
                    catch
                    {
                        // Element disappeared mid-walk; ignore cleanly
                    }
                }

            EnqueueChildren:
                if (depth < 9)
                {
                    try
                    {
                        AutomationElement child = walker.GetFirstChild(el);
                        int childCount = 0;
                        while (child != null && childCount < 64 && sw.ElapsedMilliseconds < budgetMs)
                        {
                            queue.Enqueue(Tuple.Create(child, depth + 1));
                            child = walker.GetNextSibling(child);
                            childCount++;
                        }
                    }
                    catch { }
                }
            }

            return new Dictionary<string, object>
            {
                { "ok", true },
                { "hwnd", hwnd.ToInt64() },
                { "window_title", root.Current.Name ?? "" },
                { "window_rect", new Dictionary<string, object> { { "x", (int)wx }, { "y", (int)wy }, { "width", (int)ww }, { "height", (int)wh } } },
                { "elements", elements },
                { "readable_text", readable },
                { "visited", visited },
                { "elapsed_ms", sw.ElapsedMilliseconds }
            };
        }

        private static AutomationElement FindByRuntimeId(AutomationElement root, string targetRid, int budgetMs)
        {
            if (string.IsNullOrEmpty(targetRid)) return null;
            var sw = Stopwatch.StartNew();
            var queue = new Queue<AutomationElement>();
            queue.Enqueue(root);
            var walker = TreeWalker.ControlViewWalker;
            int visited = 0;

            while (queue.Count > 0 && visited < 1500 && sw.ElapsedMilliseconds < budgetMs)
            {
                var el = queue.Dequeue();
                visited++;
                try
                {
                    string rid = RuntimeIdToString(el.GetRuntimeId());
                    if (rid == targetRid) return el;
                }
                catch { }

                try
                {
                    AutomationElement child = walker.GetFirstChild(el);
                    int c = 0;
                    while (child != null && c < 80)
                    {
                        queue.Enqueue(child);
                        child = walker.GetNextSibling(child);
                        c++;
                    }
                }
                catch { }
            }
            return null;
        }

        private static Dictionary<string, object> ExecuteAction(long hwndVal, string runtimeId, string action, string value)
        {
            EnsureInputDesktop();
            IntPtr hwnd = hwndVal > 0 ? new IntPtr(hwndVal) : GetForegroundWindow();
            if (hwnd == IntPtr.Zero || !IsWindow(hwnd))
            {
                return new Dictionary<string, object> { { "ok", false }, { "error", "Invalid window handle" } };
            }

            AutomationElement root = AutomationElement.FromHandle(hwnd);
            AutomationElement target = FindByRuntimeId(root, runtimeId, 800);
            if (target == null)
            {
                return new Dictionary<string, object> { { "ok", false }, { "error", "Element no longer present in UI tree" } };
            }

            object pat;
            if (action == "set_value")
            {
                if (target.TryGetCurrentPattern(ValuePattern.Pattern, out pat))
                {
                    ((ValuePattern)pat).SetValue(value ?? "");
                    return new Dictionary<string, object> { { "ok", true }, { "method", "ValuePattern" } };
                }
                try { target.SetFocus(); } catch { }
                return new Dictionary<string, object> { { "ok", false }, { "fallback_paste", true }, { "error", "Element does not support ValuePattern; focused instead" } };
            }

            if (action == "focus")
            {
                target.SetFocus();
                return new Dictionary<string, object> { { "ok", true }, { "method", "SetFocus" } };
            }

            if (action == "invoke" || action == "toggle" || action == "select")
            {
                if (target.TryGetCurrentPattern(InvokePattern.Pattern, out pat))
                {
                    ((InvokePattern)pat).Invoke();
                    return new Dictionary<string, object> { { "ok", true }, { "method", "InvokePattern" } };
                }
                if (target.TryGetCurrentPattern(TogglePattern.Pattern, out pat))
                {
                    ((TogglePattern)pat).Toggle();
                    return new Dictionary<string, object> { { "ok", true }, { "method", "TogglePattern" } };
                }
                if (target.TryGetCurrentPattern(SelectionItemPattern.Pattern, out pat))
                {
                    ((SelectionItemPattern)pat).Select();
                    return new Dictionary<string, object> { { "ok", true }, { "method", "SelectionItemPattern" } };
                }
                if (target.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out pat))
                {
                    var ec = (ExpandCollapsePattern)pat;
                    if (ec.Current.ExpandCollapseState == ExpandCollapseState.Collapsed) ec.Expand();
                    else ec.Collapse();
                    return new Dictionary<string, object> { { "ok", true }, { "method", "ExpandCollapsePattern" } };
                }
                try { target.SetFocus(); } catch { }
                return new Dictionary<string, object> { { "ok", false }, { "fallback_click", true }, { "error", "No direct invoke pattern; use coordinate click" } };
            }

            return new Dictionary<string, object> { { "ok", false }, { "error", "Unsupported UIA action: " + action } };
        }
    }
}
