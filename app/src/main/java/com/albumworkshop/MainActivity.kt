package com.albumworkshop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.albumworkshop.ui.GalleryViewModel
import com.albumworkshop.ui.RootScreen
import com.albumworkshop.ui.theme.AlbumWorkshopTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlbumWorkshopTheme {
                val viewModel: GalleryViewModel = viewModel()
                RootScreen(viewModel = viewModel)
            }
        }
    }
}
