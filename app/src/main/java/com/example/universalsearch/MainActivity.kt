// MainActivity.kt
// The main activity of the Universal Search App, handling UI and user interactions.
package com.example.universalsearch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.universalsearch.data.AppEntry
import com.example.universalsearch.data.ContactEntry
import com.example.universalsearch.data.FileEntry
import com.example.universalsearch.database.AppDatabase
import com.example.universalsearch.model.SearchResult
import com.example.universalsearch.ui.theme.UniversalSearchTheme
import com.example.universalsearch.viewmodel.SearchViewModel

// Constants for widget actions, used to differentiate intents coming from the widget
const val ACTION_SEARCH = "com.example.universalsearch.ACTION_SEARCH"
const val ACTION_VOICE_SEARCH = "com.example.universalsearch.ACTION_VOICE_SEARCH"

class MainActivity : ComponentActivity() {

// Lazily initialize the ViewModel  
private val searchViewModel: SearchViewModel by viewModels()  
private val SPEECH_REQUEST_CODE = 1001 // Request code for speech recognition intent  

// ActivityResultLauncher for requesting multiple permissions  
private val requestPermissionsLauncher = registerForActivityResult(  
    ActivityResultContracts.RequestMultiplePermissions()  
) { permissions ->  
    // Check if all requested permissions are granted  
    val allGranted = permissions.entries.all { it.value }  
    if (allGranted) {  
        Toast.makeText(this, "All necessary permissions granted!", Toast.LENGTH_SHORT).show()  
        // Re-initialize data sources if permissions were just granted (e.g., on first launch)  
        searchViewModel.viewModelScope.launch {  
            // Clear existing data to ensure fresh load with new permissions  
            AppDatabase.getDatabase(application).appDao().deleteAllApps()  
            AppDatabase.getDatabase(application).contactDao().deleteAllContacts()  
            AppDatabase.getDatabase(application).fileDao().deleteAllFiles()  
            searchViewModel.initializeData() // Re-load data after permissions  
        }  
    } else {  
        Toast.makeText(this, "Permissions denied. Some features may not work.", Toast.LENGTH_LONG).show()  
    }  
}  

override fun onCreate(savedInstanceState: Bundle?) {  
    super.onCreate(savedInstanceState)  

    // Handle incoming intent (e.g., from widget or shared text)  
    handleIntent(intent)  

    setContent {  
        UniversalSearchTheme {  
            Surface(  
                modifier = Modifier.fillMaxSize(),  
                color = MaterialTheme.colorScheme.background  
            ) {  
                // Collect ViewModel states as Compose states  
                val searchText by searchViewModel.searchText.collectAsStateWithLifecycle()  
                val searchResults by searchViewModel.searchResults.collectAsStateWithLifecycle()  
                val isSearching by searchViewModel.isSearching.collectAsStateWithLifecycle()  
                val showMicButton by searchViewModel.showMicButton.collectAsStateWithLifecycle()  

                // Main Composable for the search screen  
                UniversalSearchScreen(  
                    searchText = searchText,  
                    onSearchTextChange = searchViewModel::onSearchTextChange,  
                    searchResults = searchResults,  
                    isSearching = isSearching,  
                    showMicButton = showMicButton,  
                    onSearchItemClick = searchViewModel::handleSearchResultClick,  
                    onWebSearchClick = searchViewModel::performWebSearch,  
                    onMicButtonClick = { startVoiceInput() } // Callback to start voice input  
                )  
            }  
        }  
    }  

    // Request necessary permissions on activity creation  
    requestNecessaryPermissions()  
}  

// Called when a new intent is delivered to this activity (e.g., from widget after activity is running)  
override fun onNewIntent(intent: Intent?) {  
    super.onNewIntent(intent)  
    setIntent(intent) // Update the activity's current intent  
    handleIntent(intent) // Process the new intent  
}  

/**  
 * Handles various incoming intents to set the search query or trigger voice input.  
 */  
private fun handleIntent(intent: Intent?) {  
    intent?.let {  
        when (it.action) {  
            ACTION_SEARCH -> { // From widget click (search bar)  
                val query = it.getStringExtra(RecognizerIntent.EXTRA_RESULTS) ?: it.getStringExtra(Intent.EXTRA_PROCESS_TEXT)  
                query?.let { text ->  
                    searchViewModel.onSearchTextChange(text)  
                }  
            }  
            ACTION_VOICE_SEARCH -> { // From widget mic button click  
                startVoiceInput()  
            }  
            Intent.ACTION_PROCESS_TEXT -> { // From text selection "Process text" menu  
                val query = it.getStringExtra(Intent.EXTRA_PROCESS_TEXT)  
                query?.let { text ->  
                    searchViewModel.onSearchTextChange(text)  
                }  
            }  
            android.content.Intent.ACTION_SEARCH -> { // From system global search  
                val query = it.getStringExtra(android.app.SearchManager.QUERY)  
                query?.let { text ->  
                    searchViewModel.onSearchTextChange(text)  
                }  
            }  
        }  
    }  
}  

/**  
 * Requests necessary runtime permissions for the app's functionality.  
 */  
private fun requestNecessaryPermissions() {  
    val permissionsToRequest = mutableListOf<String>()  

    // Permission for app search (QUERY_ALL_PACKAGES for Android 11+)  
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  
        // QUERY_ALL_PACKAGES is a sensitive permission. In a real app, you'd need strong justification  
        // and potentially declare it in the Play Console. For this example, we request it directly.  
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.QUERY_ALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {  
            permissionsToRequest.add(Manifest.permission.QUERY_ALL_PACKAGES)  
        }  
    }  

    // Permission for contact search  
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {  
        permissionsToRequest.add(Manifest.permission.READ_CONTACTS)  
    }  

    // Permissions for file search (Scoped Storage handling for Android 10+)  
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+  
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {  
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)  
        }  
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {  
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)  
        }  
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {  
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)  
        }  
    } else { // Android 12 and below  
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {  
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)  
        }  
    }  

    // Permission for voice input  
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {  
        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)  
    }  

    // Launch the permission request if any permissions are needed  
    if (permissionsToRequest.isNotEmpty()) {  
        requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())  
    }  
}  

/**  
 * Starts the system's speech recognition activity.  
 */  
private fun startVoiceInput() {  
    // Check if speech recognition is available on the device  
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {  
        Toast.makeText(this, "Speech recognition not available on this device.", Toast.LENGTH_SHORT).show()  
        return  
    }  

    // Create an intent for speech recognition  
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {  
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)  
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") // Specify language, can be dynamic  
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...") // Prompt for the user  
    }  
    try {  
        // Start the speech recognition activity, expecting a result back  
        startActivityForResult(intent, SPEECH_REQUEST_CODE)  
    } catch (e: Exception) {  
        Toast.makeText(this, "Error starting speech recognition: ${e.message}", Toast.LENGTH_SHORT).show()  
        e.printStackTrace()  
    }  
}  

/**  
 * Callback for results from activities started with `startActivityForResult`.  
 * This method is deprecated in API 33+, but still used for SpeechRecognizer for simplicity.  
 * For newer APIs, consider using `ActivityResultContracts.StartActivityForResult`.  
 */  
@Deprecated("Deprecated in API 33")  
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {  
    super.onActivityResult(requestCode, resultCode, data)  
    if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {  
        // Get the list of recognized speech results  
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)  
        searchViewModel.handleVoiceResult(results) // Pass results to ViewModel  
    }  
}

}

/**

Composable function for the main Universal Search Screen UI.

@param searchText The current text in the search bar.

@param onSearchTextChange Callback for when the search text changes.

@param searchResults List of search results to display.

@param isSearching Boolean indicating if a search is in progress.

@param showMicButton Boolean to control mic button visibility.

@param onSearchItemClick Callback for when a search result item is clicked.

@param onWebSearchClick Callback for when the "Search web" button is clicked.

@param onMicButtonClick Callback for when the mic button is clicked.
*/
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalSearchScreen(
searchText: String,
onSearchTextChange: (String) -> Unit,
searchResults: List<SearchResult>,
isSearching: Boolean,
showMicButton: Boolean,
onSearchItemClick: (SearchResult) -> Unit,
onWebSearchClick: (String) -> Unit,
onMicButtonClick: () -> Unit
) {
val context = LocalContext.current
val focusManager = LocalFocusManager.current // To clear focus from the search bar

Scaffold(
topBar = {
Column(
modifier = Modifier
.fillMaxWidth()
.padding(8.dp)
) {
// OutlinedTextField mimicking the Google Search bar design
OutlinedTextField(
value = searchText,
onValueChange = onSearchTextChange,
modifier = Modifier
.fillMaxWidth()
.clip(RoundedCornerShape(28.dp)), // Rounded corners
placeholder = { Text("Search apps, contacts, files, or web") },
leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
trailingIcon = {
if (showMicButton) {
// Mic button when search text is empty
IconButton(onClick = onMicButtonClick) {
Icon(Icons.Default.Mic, contentDescription = "Voice Search")
}
} else {
// Clear button when search text is not empty
if (searchText.isNotEmpty()) {
IconButton(onClick = { onSearchTextChange("") }) {
Icon(painterResource(id = R.drawable.ic_clear), contentDescription = "Clear search")
}
}
}
},
singleLine = true, // Keep input on a single line
keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), // Search action on keyboard
keyboardActions = KeyboardActions(onSearch = {
// If no local results, perform web search on keyboard search action
if (searchText.isNotBlank() && searchResults.isEmpty()) {
onWebSearchClick(searchText)
}
focusManager.clearFocus() // Hide keyboard
}),
colors = TextFieldDefaults.outlinedTextFieldColors(
focusedBorderColor = Color.Transparent, // No border when focused
unfocusedBorderColor = Color.Transparent, // No border when unfocused
containerColor = MaterialTheme.colorScheme.surfaceVariant // Light grey background
)
)
// Show a linear progress indicator when searching
if (isSearching) {
LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}
}
}
) { paddingValues ->
Column(
modifier = Modifier
.fillMaxSize()
.padding(paddingValues)
.padding(horizontal = 16.dp) // Horizontal padding for content
) {
// Display search results or fallback message
if (searchResults.isEmpty() && searchText.isNotBlank() && !isSearching) {
// Show message and web search button if no local results found
Column(
modifier = Modifier.fillMaxSize(),
horizontalAlignment = Alignment.CenterHorizontally,
verticalArrangement = Arrangement.Center
) {
Text("No local results found.", style = MaterialTheme.typography.bodyLarge)
Spacer(modifier = Modifier.height(8.dp))
Button(onClick = { onWebSearchClick(searchText) }) {
Text("Search web for "$searchText"")
}
}
} else if (searchResults.isNotEmpty()) {
// Display search results in a scrollable list
LazyColumn(
modifier = Modifier.fillMaxWidth(),
verticalArrangement = Arrangement.spacedBy(8.dp)
) {
items(searchResults) { result ->
SearchResultItem(result = result, onClick = { onSearchItemClick(result) })
}
}
}
}
}
}


/**

Composable function to display a single search result item.

@param result The [SearchResult] to display.

@param onClick Callback for when the item is clicked.
*/
@Composable
fun SearchResultItem(result: SearchResult, onClick: () -> Unit) {
val context = LocalContext.current
Row(
modifier = Modifier
.fillMaxWidth()
.clickable(onClick = onClick) // Make the entire row clickable
.padding(vertical = 8.dp),
verticalAlignment = Alignment.CenterVertically
) {
when (result) {
is SearchResult.App -> {
val packageManager = context.packageManager
// Remember the app info and icon for performance
val appInfo = remember(result.app.packageName) {
try {
packageManager.getApplicationInfo(result.app.packageName, 0)
} catch (e: PackageManager.NameNotFoundException) {
null
}
}
if (appInfo != null) {
val icon = remember(appInfo) { appInfo.loadIcon(packageManager) }
Image(
bitmap = icon.toBitmap().asImageBitmap(), // Convert Drawable icon to ImageBitmap
contentDescription = result.app.appName,
modifier = Modifier.size(48.dp)
)
} else {
// Fallback icon if app info not found
Icon(Icons.Default.Search, contentDescription = "App", modifier = Modifier.size(48.dp))
}
Spacer(modifier = Modifier.width(16.dp))
Text(result.app.appName, style = MaterialTheme.typography.bodyLarge)
}
is SearchResult.Contact -> {
Icon(Icons.Default.Person, contentDescription = "Contact", modifier = Modifier.size(48.dp))
Spacer(modifier = Modifier.width(16.dp))
Column {
Text(result.contact.displayName, style = MaterialTheme.typography.bodyLarge)
result.contact.phoneNumber?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
}
}
is SearchResult.File -> {
Icon(Icons.Default.Description, contentDescription = "File", modifier = Modifier.size(48.dp))
Spacer(modifier = Modifier.width(16.dp))
Column {
Text(result.file.fileName, style = MaterialTheme.typography.bodyLarge)
Text(result.file.fileType, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}
}
}
}
}


/**

Preview Composable for the Universal Search Screen.
*/
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
UniversalSearchTheme {
UniversalSearchScreen(
searchText = "example",
onSearchTextChange = {},
searchResults = listOf(
SearchResult.App(AppEntry("com.example.app1", "Example App 1")),
SearchResult.Contact(ContactEntry(1, "John Doe", "123-456-7890", "john@example.com")),
SearchResult.File(FileEntry("/path/to/doc.pdf", "MyDocument.pdf", "application/pdf", 123456789, 102400))
),
isSearching = false,
showMicButton = false,
onSearchItemClick = {},
onWebSearchClick = {},
onMicButtonClick = {}
)
}
}


