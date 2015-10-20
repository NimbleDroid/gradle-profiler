# Gradle Profiler

A gradle plugin that interfaces with NimbleDroid to automate app profiling.

## Using Profiler

Build script snippet:

    buildscript {
        repositories {
            maven {
                url "https://raw.githubusercontent.com/Tubebaum/maven-repo/master/"
            }
        }
        dependencies {
            classpath 'com.nimbledroid:gradle-profiler:1.0'
        }
    }

    apply plugin: 'com.nimbledroid.profiler'

Specify the following in build.gradle to configure the plugin:

    nimbledroid {
        apiKey 'NimbleDroid API key'
    }

By default, the plugin uses the last application variant in your output
directory. If you would like to use a different variant, specify the following:

    nimbledroid {
        apiKey 'NimbleDroid API key'
        apkPath "$buildDir/path/to/your-release.apk"
    }

Run `gradle -q nimbleUpload` to upload your APK to NimbleDroid.  
Run `gradle -q nimbleProfile` to retrieve profiling information.  
Run `gradle -q nimbleApps` to retrieve information about your apps.
