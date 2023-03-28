package com.supersuman.hymn

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.supersuman.apkupdater.ApkUpdater
import com.supersuman.hymn.databinding.ActivityMainBinding
import com.supersuman.hymn.databinding.EachSearchResultBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {


    private lateinit var binding: ActivityMainBinding
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.94 Safari/537.36")
    private var thread = Thread()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ResultsAdapter(this, mutableListOf())

        initYoutubedl()
        initListeners()
        checkUpdate()
        isStoragePermissionGranted()

    }

    private fun initYoutubedl() {
        try {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            println(e)
        }
    }

    private fun initListeners() {
        binding.searchBar.addTextChangedListener {
            binding.recyclerView.adapter = ResultsAdapter(this, mutableListOf())
            if (it.toString().trim() == "") return@addTextChangedListener
            if (!thread.isInterrupted) thread.interrupt()
            thread = Thread {
                runOnUiThread { binding.progressBar.isIndeterminate = true }
                try {
                    val videoIds = getVideoIds(it.toString())
                    val results = getVideoInfo(videoIds)
                    runOnUiThread { binding.recyclerView.adapter = ResultsAdapter(this, results) }
                } catch (e: Exception) {
                    println(e)
                } finally {
                    runOnUiThread { binding.progressBar.isIndeterminate = false }
                }
            }
            thread.start()
        }
    }

    private fun getVideoIds(searchText: String): MutableList<String> {
        val videoIds = mutableListOf<String>()
        val response = khttp.get("https://music.youtube.com/search?q=${searchText}", headers = headers).text
        val decoded = decode(response)
        val results = Regex("(?<=\"videoId\":\")(.+?)(?=\")").findAll(decoded, 0)
        results.forEach {
            if (it.value !in videoIds) videoIds.add(it.value)
        }
        return videoIds
    }

    private fun getVideoInfo(videoIds: MutableList<String>): MutableList<JSONObject> {
        val mutableList = mutableListOf<JSONObject>()
        videoIds.forEach {
            val videoLink = "https://noembed.com/embed?url=https://www.youtube.com/watch?v=$it"
            val response = khttp.get(videoLink, headers = headers).text
            val json = JSONObject(response)
            mutableList.add(json)
            println(json)
        }
        return mutableList
    }

    private fun decode(string: String): String {
        return string.replace("\\x22", "\"").replace("\\x28", "(").replace("\\x29", ")").replace("\\x7b", "{").replace("\\x7d", "}").replace("\\x5b", "[").replace("\\x5d", "]").replace("\\x3d", "=")
            .replace("\\/", "/")
    }

    private fun checkUpdate() {
        thread {
            val updater = ApkUpdater(this, "https://github.com/supersu-man/hymn/releases/latest")
            updater.threeNumbers = true
            if (updater.isInternetConnection() && updater.isNewUpdateAvailable() == true) {
                val dialog = MaterialAlertDialogBuilder(this).setTitle("Download new update?").setPositiveButton("Yes") { _, _ ->
                    thread { updater.requestDownload() }
                }.setNegativeButton("No") { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }
                runOnUiThread { dialog.show() }
            }
        }
    }

    private fun isStoragePermissionGranted() {
        val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && perm != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }
}


class ResultsAdapter(private val activity: MainActivity, private val results: MutableList<JSONObject>) : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
    class ViewHolder(val binding: EachSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = EachSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.title.text = results[position]["title"] as String
        holder.binding.author.text = results[position]["author_name"] as String
        thread {
            val url = URL(results[position]["thumbnail_url"] as String)
            val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
            activity.runOnUiThread {
                holder.binding.image.setImageBitmap(bitmap)
            }
        }
        holder.binding.downloadButton.setOnClickListener {
            download(results[position]["url"] as String)
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun download(videoLink: String) {
        val alertDialog = MaterialAlertDialogBuilder(activity).create()
        val downloadProgessIndicator = LinearProgressIndicator(activity)
        alertDialog.setMessage("Downloading...")
        alertDialog.setView(downloadProgessIndicator, 80, 20, 80, 0)
        alertDialog.setCancelable(false)
        thread {
            try {
                activity.runOnUiThread { alertDialog.show() }
                val youtubeDLDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hymn")
                val request = YoutubeDLRequest(videoLink)
                request.addOption("-o", youtubeDLDir.absolutePath.toString() + "/%(title)s.%(ext)s")
                request.addOption("--audio-format", "mp3")
                request.addOption("-x")
                request.addOption("--yes-overwrites")
                YoutubeDL.getInstance().execute(request) { progress: Float, etaInSeconds: Long ->
                    activity.runOnUiThread { downloadProgessIndicator.progress = progress.toInt() }
                    println("$progress% (ETA $etaInSeconds seconds)")
                }
                activity.runOnUiThread {
                    downloadProgessIndicator.progress = 100
                }
            } catch (e: Exception) {
                println(e)
            } finally {
                activity.runOnUiThread {
                    alertDialog.dismiss()
                }
            }
        }
    }

}