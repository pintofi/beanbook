package dev.beancount.app

import android.app.Application
import dev.beancount.core.engine.PluginPolicy

/**
 * Application-scoped wiring. The edition's [PluginPolicy] is supplied by the flavor
 * source set ([currentPluginPolicy]), so shared code never branches on build flavor and
 * the play build cannot reach expert-only behaviour by accident.
 */
class BeancountApplication : Application() {

    val pluginPolicy: PluginPolicy by lazy { currentPluginPolicy() }
}
