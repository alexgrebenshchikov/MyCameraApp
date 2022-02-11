package com.example.mycameraapp.fragments

import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.fragment.app.viewModels
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import by.kirich1409.viewbindingdelegate.viewBinding
import com.example.mycameraapp.R
import com.example.mycameraapp.databinding.FragmentGalleryBinding
import java.io.File
import java.util.*


class GalleryFragment : Fragment(R.layout.fragment_gallery) {
    companion object {
        val EXTENSION_WHITELIST = arrayOf("JPG")
    }

    private val fragmentGalleryBinding by viewBinding(FragmentGalleryBinding::bind)
    private val viewModel: GalleryViewModel by viewModels()

    private val args: GalleryFragmentArgs by navArgs()


    inner class MediaPagerAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getCount(): Int = viewModel.mediaList.size
        override fun getItem(position: Int): Fragment =
            PhotoFragment.create(viewModel.mediaList[position])

        override fun getItemPosition(obj: Any): Int = POSITION_NONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val rootDirectory = File(args.rootDirectory)


        if (!viewModel.checkMediaListIsInitialized()) {
            viewModel.mediaList = rootDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.uppercase(Locale.ROOT))
            }?.sortedDescending()?.toMutableList() ?: mutableListOf()
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.mediaList.isEmpty()) {
            fragmentGalleryBinding.deleteButton.isEnabled = false
        }

        fragmentGalleryBinding.photoViewPager.apply {
            offscreenPageLimit = 2
            adapter = MediaPagerAdapter(childFragmentManager)
        }


        fragmentGalleryBinding.backButton.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }

        fragmentGalleryBinding.deleteButton.setOnClickListener {
            viewModel.mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)
                ?.let { mediaFile ->

                    AlertDialog.Builder(view.context)
                        .setTitle(getString(R.string.delete_title))
                        .setMessage(getString(R.string.delete_dialog))
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(R.string.positive_button_text) { _, _ ->

                            mediaFile.delete()

                            MediaScannerConnection.scanFile(
                                view.context, arrayOf(mediaFile.absolutePath), null, null
                            )

                            viewModel.mediaList.removeAt(fragmentGalleryBinding.photoViewPager.currentItem)
                            fragmentGalleryBinding.photoViewPager.adapter?.notifyDataSetChanged()

                            if (viewModel.mediaList.isEmpty()) {
                                Navigation.findNavController(
                                    requireActivity(),
                                    R.id.fragment_container
                                ).navigateUp()
                            }

                        }

                        .setNegativeButton(R.string.negative_button_text, null)
                        .create().show()
                }
        }
    }


}
