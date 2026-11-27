package com.kova.workstation.data.storage

import android.content.Context
import com.kova.workstation.data.model.AppPreferences
import com.kova.workstation.data.model.CommandSnippet
import com.kova.workstation.data.model.WorkstationProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("kova_workstation_prefs", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadProfiles(): List<WorkstationProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<WorkstationProfile>>(raw) }.getOrDefault(emptyList())
    }

    fun saveProfiles(profiles: List<WorkstationProfile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(profiles)).apply()
    }

    fun upsertProfile(profile: WorkstationProfile): List<WorkstationProfile> {
        val existing = loadProfiles().toMutableList()
        val idx = existing.indexOfFirst {
            it.id == profile.id || (it.host.equals(profile.host, ignoreCase = true) && it.port == profile.port)
        }
        if (idx >= 0) {
            existing[idx] = profile.copy(id = existing[idx].id)
        } else {
            existing.add(0, profile)
        }
        saveProfiles(existing)
        return existing
    }

    fun deleteProfile(id: String): List<WorkstationProfile> {
        val updated = loadProfiles().filterNot { it.id == id }
        saveProfiles(updated)
        return updated
    }

    fun loadPreferences(): AppPreferences {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppPreferences()
        return runCatching { json.decodeFromString<AppPreferences>(raw) }.getOrDefault(AppPreferences())
    }

    fun savePreferences(settings: AppPreferences) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    fun loadSnippets(): List<CommandSnippet> {
        val raw = prefs.getString(KEY_SNIPPETS, null) ?: return defaultSnippets()
        return runCatching { json.decodeFromString<List<CommandSnippet>>(raw) }.getOrDefault(defaultSnippets())
    }

    fun saveSnippets(snippets: List<CommandSnippet>) {
        prefs.edit().putString(KEY_SNIPPETS, json.encodeToString(snippets)).apply()
    }

    private fun defaultSnippets(): List<CommandSnippet> = listOf(
        CommandSnippet(label = "Git Status", command = "git status -sb\r"),
        CommandSnippet(label = "Git Diff", command = "git diff --stat\r"),
        CommandSnippet(label = "List Files", command = "ls\r"),
        CommandSnippet(label = "Top Processes", command = "Get-Process | Sort-Object CPU -Descending | Select-Object -First 12 Name,CPU,WorkingSet\r"),
        CommandSnippet(label = "Net Ports", command = "netstat -ano | findstr LISTENING\r"),
        CommandSnippet(label = "Clear", command = "cls\r")
    )

    companion object {
        private const val KEY_PROFILES = "profiles_v1"
        private const val KEY_SETTINGS = "settings_v1"
        private const val KEY_SNIPPETS = "snippets_v1"
    }
}
