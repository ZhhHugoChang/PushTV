package com.example.pushtv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

object ReceivedFileHistoryRepo {
    private val hiddenFileNamesKey = stringSetPreferencesKey("hidden_received_files")

    suspend fun getHiddenFileNames(context: Context): Set<String> {
        return context.dataStore.data.first()[hiddenFileNamesKey].orEmpty()
    }

    suspend fun hide(context: Context, fileNames: Collection<String>) {
        if (fileNames.isEmpty()) return
        context.dataStore.edit { preferences ->
            preferences[hiddenFileNamesKey] =
                preferences[hiddenFileNamesKey].orEmpty() + fileNames
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { preferences -> preferences.remove(hiddenFileNamesKey) }
    }
}
