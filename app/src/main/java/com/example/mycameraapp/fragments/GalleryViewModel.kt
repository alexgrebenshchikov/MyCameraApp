package com.example.mycameraapp.fragments

import androidx.lifecycle.ViewModel
import java.io.File

class GalleryViewModel : ViewModel() {
    lateinit var mediaList: MutableList<File>

    fun checkMediaListIsInitialized() = this::mediaList.isInitialized
}