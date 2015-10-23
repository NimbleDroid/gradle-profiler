# Gradle Profiler

A gradle plugin that interfaces with [NimbleDroid](https://www.nimbledroid.com) to automate app profiling.

## Using Profiler

Build script snippet for use in all Gradle versions:

    buildscript {
        repositories {
            maven {
                url "https://raw.githubusercontent.com/Tubebaum/maven-repo/master/"
            }
        }
        dependencies {
            classpath 'com.nimbledroid:gradle-profiler:1.0.1'
        }
    }

    apply plugin: 'com.nimbledroid.profiler'

Build script snippet for new, incubating, plugin mechanism introduced in Gradle 2.1:

    plugins {
        id "com.nimbledroid.profiler" version "1.0.1"
    }

Specify the following in build.gradle to configure the plugin:

    nimbledroid {
        apiKey 'NimbleDroid API key'
    }

By default, the plugin uses the output last output file specified by the 'release'
variant. If you would like to use a different variant, specify the following:

    nimbledroid {
        apiKey 'NimbleDroid API key'
        variant 'paid'
    }

If your variant has multiple output files (e.g., your 'release' variant produces
'app-release-0.apk and app-release-1.apk), specify the exact apk filename instead of
the variant:

    nimbledroid {
        apiKey 'NimbleDroid API key'
        apkFilename 'app-release-0.apk'
    }

Run `gradle ndUpload` to upload your APK to NimbleDroid.  
Run `gradle ndGetProfile` to retrieve profiling information.
