package journal.gratitude.com.gratitudejournal.ui.timeline

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.analytics.FirebaseAnalytics
import journal.gratitude.com.gratitudejournal.R
import journal.gratitude.com.gratitudejournal.databinding.TimelineFragmentBinding
import journal.gratitude.com.gratitudejournal.model.EXPORTED_DATA
import journal.gratitude.com.gratitudejournal.model.IMPORTED_DATA
import journal.gratitude.com.gratitudejournal.model.LOOKED_FOR_DATA
import journal.gratitude.com.gratitudejournal.repository.EntryRepository
import journal.gratitude.com.gratitudejournal.room.EntryDatabase
import journal.gratitude.com.gratitudejournal.ui.entry.EntryFragment.Companion.DATE
import journal.gratitude.com.gratitudejournal.util.backups.ExportCallback
import journal.gratitude.com.gratitudejournal.util.backups.exportDB
import journal.gratitude.com.gratitudejournal.util.backups.parseCsv
import kotlinx.android.synthetic.main.timeline_fragment.*
import org.threeten.bp.LocalDate
import java.io.File
import java.io.IOException
import java.io.InputStream


class TimelineFragment : androidx.fragment.app.Fragment() {

    companion object {
        fun newInstance() = TimelineFragment()
        const val MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL = 512
        const val IMPORT_CSV = 206
    }

    private lateinit var viewModel: TimelineViewModel
    private lateinit var adapter: TimelineAdapter
    private lateinit var binding: TimelineFragmentBinding
    private lateinit var firebaseAnalytics: FirebaseAnalytics


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryDao = EntryDatabase.getDatabase(activity!!.application).entryDao()
        val repository = EntryRepository(entryDao) //TODO look into sharing across both fragments

        viewModel = ViewModelProviders.of(this, TimelineViewModelFactory(repository)).get(TimelineViewModel::class.java)
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        binding = TimelineFragmentBinding.inflate(inflater, container, false)
        binding.viewModel = viewModel

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        firebaseAnalytics = FirebaseAnalytics.getInstance(context!!)

        timeline_recycler_view.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(context)
        adapter = TimelineAdapter(activity!!, object : TimelineAdapter.OnClickListener {
            override fun onClick(clickedDate: LocalDate) {
                val bundle = bundleOf(DATE to clickedDate.toString())
                findNavController().navigate(
                        R.id.action_timelineFragment_to_entryFragment,
                        bundle
                )
            }
        })
        timeline_recycler_view.adapter = adapter

        overflow_button.setOnClickListener {
            PopupMenu(context, it).apply {
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.notification_settings -> {
                            openNotificationSettings()
                            true
                        }
                        R.id.contact_us -> {
                            openContactForm()
                            true
                        }
                        R.id.export_data -> {
                            exportData()
                            true
                        }
                        R.id.import_data -> {
                            importData()
                            true
                        }
                        else -> false
                    }
                }
                inflate(R.menu.overflow_menu)
                show()
            }
        }

        viewModel.entries.observe(this, Observer {
            binding.viewModel = viewModel
        })

    }

    private fun importData() {
        val alertDialog: AlertDialog? = activity?.let {
            val builder = AlertDialog.Builder(it)
            builder.apply {
                setTitle(R.string.import_data_dialog)
                setMessage(R.string.import_data_dialog_message)
                setPositiveButton(R.string.ok) { dialog, id ->
                    selectCSVFile()
                }
                setNegativeButton(R.string.cancel) { _, _ -> }
            }
            // Create the AlertDialog
            builder.create()
        }
        alertDialog?.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    exportDB(viewModel.entries.value
                            ?: emptyList(), exportCallback)
                } else {
                    Toast.makeText(context, "Permission is needed to export data", Toast.LENGTH_SHORT).show()
                }
                return
            }
            else -> {
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            IMPORT_CSV -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) {
                        if (uri.scheme == "content") {
                            val inputStream = context?.contentResolver?.openInputStream(uri)
                            if (inputStream != null) {
                                importFromCsv(inputStream)
                            } else {
                                Toast.makeText(context, "Error parsing file", Toast.LENGTH_SHORT).show()
                            }

                        }
                    } else {
                        Toast.makeText(context, "File must be a CSV", Toast.LENGTH_SHORT).show()
                    }

                }
            }
        }
    }

    private fun importFromCsv(inputStream: InputStream) {
        firebaseAnalytics.logEvent(IMPORTED_DATA, null)
        // parse file to get List<Entry>
        try {
            val entries = parseCsv(inputStream)
            viewModel.addEntries(entries)
        } catch (exception: IOException) {
            Toast.makeText(context, "Error parsing file", Toast.LENGTH_SHORT).show()
        }

    }

    private fun exportData() {
        firebaseAnalytics.logEvent(EXPORTED_DATA, null)

        val permission = ActivityCompat.checkSelfPermission(activity!!, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL)
        } else {
            exportDB(viewModel.entries.value
                    ?: emptyList(), exportCallback)
        }
    }

    private fun selectCSVFile() {
        firebaseAnalytics.logEvent(LOOKED_FOR_DATA, null)

        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        val mimeTypes = arrayOf("text/*")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        try {
            startActivityForResult(Intent.createChooser(intent, "Select"), IMPORT_CSV)
        } catch (ex: android.content.ActivityNotFoundException) {
            // Potentially direct the user to the Market with a Dialog
            Toast.makeText(context, "File viewer not found", Toast.LENGTH_SHORT).show()
        }

    }

    private fun openContactForm() {
        val intent = Intent(Intent.ACTION_VIEW)
        val subject = "In App Feedback"
        val data = Uri.parse("mailto:gratitude.journal.app@gmail.com?subject=$subject")
        intent.data = data
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        findNavController().navigate(
                R.id.action_timelineFragment_to_settingsFragment)
    }

    private val exportCallback: ExportCallback = object : ExportCallback {
        override fun onSuccess(file: File) {
            Snackbar.make(container, R.string.export_success, Snackbar.LENGTH_LONG)
                    .setAction(R.string.open) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW)
                            val apkURI = FileProvider.getUriForFile(context!!,
                                    context?.applicationContext?.packageName + ".provider", file)
                            intent.setDataAndType(apkURI, "text/csv")
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
        }

        override fun onFailure(message: String) {
            Toast.makeText(context, "Error exporting: $message", Toast.LENGTH_SHORT).show()
        }
    }

}

