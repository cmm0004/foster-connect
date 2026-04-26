package com.example.fosterconnect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val navItemIds = mapOf(
        R.id.nav_current_fosters to R.id.FosterListFragment,
        R.id.nav_alerts to R.id.MessageCenterFragment,
        R.id.nav_previous_fosters to R.id.PreviousFostersFragment,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val headerView = LayoutInflater.from(this)
            .inflate(R.layout.toolbar_header, binding.toolbar, false)
        binding.toolbar.addView(headerView)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.FosterListFragment, R.id.MessageCenterFragment, R.id.PreviousFostersFragment), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        val drawerContent = binding.navDrawer.root
        drawerContent.post {
            val screenWidth = resources.displayMetrics.widthPixels
            drawerContent.layoutParams = drawerContent.layoutParams.apply {
                width = (screenWidth * 0.80).toInt()
            }
        }

        setupDrawerNavigation(drawerLayout)
        setupDrawerContent()
        observeDrawerCounts()

        binding.fab.visibility = View.GONE
    }

    private fun setupDrawerNavigation(drawerLayout: DrawerLayout) {
        val drawerView = binding.navDrawer.root

        for ((viewId, destId) in navItemIds) {
            drawerView.findViewById<View>(viewId)?.setOnClickListener {
                navController.navigate(destId)
                drawerLayout.closeDrawers()
                updateActiveNavItem(viewId)
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val activeViewId = navItemIds.entries
                .firstOrNull { it.value == destination.id }?.key
            if (activeViewId != null) {
                updateActiveNavItem(activeViewId)
            }
        }
    }

    private fun updateActiveNavItem(activeViewId: Int) {
        val drawerView = binding.navDrawer.root

        data class NavItemConfig(
            val viewId: Int,
            val iconId: Int,
            val labelId: Int,
        )

        val items = listOf(
            NavItemConfig(R.id.nav_current_fosters, R.id.nav_current_fosters_icon, R.id.nav_current_fosters_label),
            NavItemConfig(R.id.nav_alerts, R.id.nav_alerts_icon, R.id.nav_alerts_label),
            NavItemConfig(R.id.nav_previous_fosters, R.id.nav_previous_fosters_icon, R.id.nav_previous_fosters_label),
        )

        for (item in items) {
            val container = drawerView.findViewById<LinearLayout>(item.viewId)
            val icon = drawerView.findViewById<ImageView>(item.iconId)
            val label = drawerView.findViewById<TextView>(item.labelId)
            val isActive = item.viewId == activeViewId

            container.setBackgroundResource(
                if (isActive) R.drawable.nav_item_active_bg else R.drawable.nav_item_default_bg
            )
            icon.setColorFilter(
                ContextCompat.getColor(this,
                    if (isActive) R.color.clinical_sage else R.color.clinical_ink_muted
                )
            )
            label.setTextColor(
                ContextCompat.getColor(this,
                    if (isActive) R.color.clinical_sage else R.color.clinical_ink
                )
            )
            if (isActive) {
                label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
            } else {
                label.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun setupDrawerContent() {
        val drawerView = binding.navDrawer.root

        drawerView.findViewById<TextView>(R.id.drawer_session_name)?.text =
            getString(R.string.drawer_litter_placeholder)

        drawerView.findViewById<TextView>(R.id.drawer_overdue_badge)?.text =
            getString(R.string.drawer_overdue_format, 0)
        drawerView.findViewById<TextView>(R.id.drawer_in_care_badge)?.text =
            getString(R.string.drawer_in_care_format, 0)

        drawerView.findViewById<TextView>(R.id.nav_current_fosters_sub)?.text =
            getString(R.string.drawer_sub_current_format, 0, 0)
        drawerView.findViewById<TextView>(R.id.nav_alerts_sub)?.text =
            getString(R.string.drawer_sub_alerts_format, 0)
        drawerView.findViewById<TextView>(R.id.nav_previous_fosters_sub)?.text =
            getString(R.string.drawer_sub_previous_format, 0)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        val dateStr = java.text.SimpleDateFormat("MMM dd yyyy", java.util.Locale.US)
            .format(java.util.Date()).uppercase()
        drawerView.findViewById<TextView>(R.id.drawer_build_info)?.text =
            getString(R.string.drawer_build_info_format, versionName, dateStr)
    }

    private fun observeDrawerCounts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    KittenRepository.activeFostersFlow,
                    KittenRepository.completedFostersFlow
                ) { active, completed ->
                    active to completed.size
                }.collect { (active, previousCount) ->
                    val litterName = active.firstOrNull()?.litterName
                    val drawerView = binding.navDrawer.root
                    drawerView.findViewById<TextView>(R.id.drawer_session_name)?.text =
                        litterName ?: getString(R.string.drawer_litter_placeholder)
                    updateDrawerCounts(active.size, 0, 0, previousCount)
                }
            }
        }
    }

    fun updateDrawerCounts(inCare: Int, overdue: Int, unreadAlerts: Int, previousCount: Int) {
        val drawerView = binding.navDrawer.root

        drawerView.findViewById<TextView>(R.id.drawer_overdue_badge)?.apply {
            text = getString(R.string.drawer_overdue_format, overdue)
            visibility = if (overdue > 0) View.VISIBLE else View.GONE
        }
        drawerView.findViewById<TextView>(R.id.drawer_in_care_badge)?.text =
            getString(R.string.drawer_in_care_format, inCare)

        drawerView.findViewById<TextView>(R.id.nav_current_fosters_sub)?.text =
            getString(R.string.drawer_sub_current_format, inCare, overdue)
        drawerView.findViewById<TextView>(R.id.nav_current_fosters_badge)?.apply {
            if (overdue > 0) {
                text = overdue.toString()
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        drawerView.findViewById<TextView>(R.id.nav_alerts_sub)?.text =
            getString(R.string.drawer_sub_alerts_format, unreadAlerts)
        drawerView.findViewById<TextView>(R.id.nav_alerts_badge)?.apply {
            if (unreadAlerts > 0) {
                text = unreadAlerts.toString()
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        drawerView.findViewById<TextView>(R.id.nav_previous_fosters_sub)?.text =
            getString(R.string.drawer_sub_previous_format, previousCount)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}
