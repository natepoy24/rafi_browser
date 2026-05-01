package com.rafbrow.rafibrowser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MenuBottomSheet : BottomSheetDialogFragment() {

    interface MenuListener {
        fun onForwardClicked()
        fun onFullscreenClicked()
        fun onAddBookmarkClicked()
    }

    var listener: MenuListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayout>(R.id.menuForward).setOnClickListener {
            listener?.onForwardClicked()
            dismiss()
        }

        view.findViewById<LinearLayout>(R.id.menuFullscreen).setOnClickListener {
            listener?.onFullscreenClicked()
            dismiss()
        }

        view.findViewById<LinearLayout>(R.id.menuAddBookmark).setOnClickListener {
            listener?.onAddBookmarkClicked()
            dismiss()
        }

        view.findViewById<LinearLayout>(R.id.optBookmarks).setOnClickListener {
            startActivity(Intent(requireContext(), BookmarksActivity::class.java))
            dismiss()
        }

        view.findViewById<LinearLayout>(R.id.optHistory).setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
            dismiss()
        }

        view.findViewById<LinearLayout>(R.id.optDownloads).setOnClickListener {
            startActivity(Intent(requireContext(), DownloadsActivity::class.java))
            dismiss()
        }

        view.findViewById<LinearLayout>(R.id.optSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }
    }
}
