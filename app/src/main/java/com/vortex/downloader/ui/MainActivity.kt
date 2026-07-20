package com.vortex.downloader.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.vortex.downloader.R
import com.vortex.downloader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = navHost.navController

        // NOT: setupWithNavController yerine elle kuruyoruz ki sekmeler arası
        // geçişte (Ana ekran ↔ Geçmiş) yumuşak bir fade+slide animasyonu olsun.
        // Mantık NavigationUI'nin kendi implementasyonuyla aynı (launchSingleTop +
        // restoreState + start destination'a popUpTo), sadece animasyonlar eklendi.
        binding.bottomNav.setOnItemSelectedListener { item ->
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.fade_out)
                .setPopUpTo(navController.graph.startDestinationId, false, true)
                .build()
            try {
                navController.navigate(item.itemId, null, options)
                true
            } catch (e: Exception) {
                false
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.menu.findItem(destination.id)?.isChecked = true
        }

        // Setup durumunu gözlemle — hazır değilse SetupFragment'e git
        viewModel.setupState.observe(this) { state ->
            when (state) {
                MainViewModel.SetupState.READY -> {
                    binding.bottomNav.visibility = View.VISIBLE
                }
                else -> {
                    binding.bottomNav.visibility = View.GONE
                }
            }
        }

        // Tarayıcıdan paylaşılan URL
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND &&
            intent.type == "text/plain") {
            val sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedUrl.isNullOrBlank()) {
                // HomeFragment'e URL gönder
                supportFragmentManager.setFragmentResult(
                    "shared_url",
                    Bundle().apply { putString("url", sharedUrl) }
                )
            }
        }
    }
}
