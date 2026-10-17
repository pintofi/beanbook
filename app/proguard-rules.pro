# R8 keep rules.
#
# Everything here exists because something calls it by name at runtime, where R8 cannot see
# the call. Each rule says who does the calling — a keep rule without a reason is a rule
# nobody can ever safely delete.

# --- Chaquopy and the Java/Python bridge --------------------------------------------------
# Python code addresses Java classes by name (`from com.chaquo.python import Python`), and
# Chaquopy's runtime reflects into its own machinery. None of that is visible to R8.
-keep class com.chaquo.python.** { *; }
-keep class org.python.** { *; }
-dontwarn com.chaquo.python.**

# --- AIDL -----------------------------------------------------------------------------------
# The engine runs in its own process and is reached through generated Binder stubs, which are
# instantiated reflectively across the process boundary.
-keep class io.github.pintofi.beanbook.engine.local.IEngineService { *; }
-keep class io.github.pintofi.beanbook.engine.local.IEngineService$* { *; }

# --- Widgets and receivers ---------------------------------------------------------------
# AppWidgetProviders are instantiated by the system from the name in the manifest.
-keep class io.github.pintofi.beanbook.app.widget.** extends android.appwidget.AppWidgetProvider { *; }

# --- Argon2 ------------------------------------------------------------------------------
# JNI: the native side looks the class up by name.
-keep class com.lambdapioneer.argon2kt.** { *; }
