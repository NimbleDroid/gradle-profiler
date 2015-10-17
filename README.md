# Gradle Profiler

A gradle plugin that interfaces with NimbleDroid to automate app profiling.

## Using Profiler

Specify the following in build.gradle:

    nimbledroid {
        apiKey 'NimbleDroid API key'
        password 'NimbleDroid password'
        apkPath "$buildDir/path/to/your-release.apk"
    }

Run `gradle -q nimbleUpload` to upload your APK to NimbleDroid  
Run `gradle -q nimbleProfile` to retrieve profiling information  
Run `gradle -q nimbleApps` to retrieve information about your apps
