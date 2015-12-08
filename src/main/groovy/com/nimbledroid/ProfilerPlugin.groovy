package com.nimbledroid

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.POST
import java.util.concurrent.TimeUnit
import static org.apache.http.entity.ContentType.APPLICATION_JSON
import static org.apache.http.entity.ContentType.TEXT_PLAIN
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.StopActionException

class ProfilerPluginExtension {
    String apiKey = null
    String variant = 'release'
    String apkFilename = null
    String apkMapping = null
    long ndGetProfileTimeout = 1800
    String server = 'https://www.nimbledroid.com'
}

class AppDataExtension {
    String username = null
    String password = null
}

class ProfilerPlugin implements Plugin<Project> {
    HTTPBuilder http = null
    File nimbleProperties = null
    String nimbleVersion = null
    Boolean greetingLock = false

    void apply(Project project) {
        project.extensions.create('nimbledroid', ProfilerPluginExtension)
        project.nimbledroid.extensions.create('appData', AppDataExtension)

        nimbleProperties = project.file("$project.rootDir/nimbledroid.properties")
        nimbleVersion = '1.0.6'

        project.task('ndUpload') << {
            greeting(project)
            http = new HTTPBuilder(project.nimbledroid.server)
            checkKey(project)
            http.auth.basic(project.nimbledroid.apiKey, '')
            String apkPath = null
            File apk = null
            File mapping = null
            if (project.nimbledroid.apkFilename) {
                apk = project.file("build/outputs/apk/$project.nimbledroid.apkFilename")
                if (!apk.exists()) {
                    apk = new File(project.nimbledroid.apkFilename)
                    if (!apk.exists()) {
                        println "Could not find ${apk.getAbsolutePath()}"
                        ndFailure('apkFilenameError')
                    }
                }
            } else if (project.hasProperty('android')) {
                project.android.applicationVariants.all { variant ->
                    variant.outputs.each { output ->
                        if (variant.name == project.nimbledroid.variant) {
                            apkPath = output.outputFile
                            mapping = variant.mappingFile
                        }
                    }
                }
                if (apkPath == null) {
                    println "No variant named $project.nimbledroid.variant"
                    ndFailure('variantNameError')
                }
                apk = project.file(apkPath)
                if (!apk.exists()) {
                    println "No apk exists for variant $project.nimbledroid.variant"
                    ndFailure('variantApkError')
                }
            } else {
                println "Could not find android block. Please apply the plugin to your app's build.gradle or define an apkFilename"
                ndFailure('androidError')
            }
            if (project.nimbledroid.apkMapping) {
                mapping = new File(project.nimbledroid.apkMapping)
                if (!mapping.exists()) {
                    println "Could not find ${mapping.getAbsolutePath()}"
                    ndFailure('mappingError')
                }
            }
            String errorMessage = null
            http.request(POST, JSON) { req ->
                uri.path = '/api/v1/apks'
                headers.'User-Agent' = "NimbleDroid Profiler Gradle Plugin/$nimbleVersion"
                headers.'gradle' = nimbleVersion
                requestContentType = 'multipart/form-data'
                MultipartEntity entity = new MultipartEntity()
                entity.addPart('apk', new FileBody(apk))
                if (mapping) {
                    entity.addPart('mapping', new FileBody(mapping))
                    entity.addPart('has_mapping', new StringBody('true'))
                }
                try {
                    String commitHash = 'git rev-parse HEAD'.execute().text.trim()
                    if (commitHash) {
                        entity.addPart('commit', new StringBody(commitHash));
                    }
                } catch (IOException e) {}
                if (project.hasProperty('branch') && project.branch) {
                    entity.addPart('branch', new StringBody(project.branch));
                }
                if (project.hasProperty('flavor') && project.flavor) {
                    entity.addPart('flavor', new StringBody(project.flavor));
                }
                if (project.nimbledroid.hasProperty('appData')) {
                    if (project.nimbledroid.appData.username || project.nimbledroid.appData.password) {
                        entity.addPart('auto_login', new StringBody('true'));
                    }
                    if (project.nimbledroid.appData.username) {
                        entity.addPart('username', new StringBody(project.nimbledroid.appData.username));
                    }
                    if (project.nimbledroid.appData.password) {
                        entity.addPart('password', new StringBody(project.nimbledroid.appData.password));
                    }
                }
                req.entity = entity
                response.success = { resp, reader ->
                    if (reader.console_message) {
                        println reader.console_message
                    }
                    if (reader.profile_url) {
                        println "Profile URL: $reader.profile_url"
                        nimbleProperties.write(reader.profile_url)
                    }
                }
                response.'401' = { resp ->
                      println "Invalid API key, visit $project.nimbledroid.server/account to retrieve the current key."
                      println 'You can contact support@nimbledroid.com if you need assistance.'
                      errorMessage = 'invalidKeyError'
                }
                response.failure = { resp ->
                    println "There was a problem reaching the NimbleDroid service ($project.nimbledroid.server$uri.path)."
                    println 'You can contact support@nimbledroid.com if you need assistance.'
                    errorMessage = 'requestError'
                }
            }
            if (errorMessage) {
                ndFailure(errorMessage)
            }
        }

        project.task('ndGetProfile') << {
            greeting(project)
            http = new HTTPBuilder(project.nimbledroid.server)
            checkKey(project)
            if (!nimbleProperties.exists()) {
                println "Couldn\'t find nimbledroid.properties file, ndUpload task was either not run or failed."
                ndFailure('propertiesError')
            }
            http.auth.basic(project.nimbledroid.apiKey, '')
            Boolean done = false
            String latestProfile = new String(nimbleProperties.readBytes()).trim()
            String uriPath = "/api/v1$latestProfile"
            try {
                URL url = new URL(latestProfile)
                uriPath = url.getPath()
            } catch (MalformedURLException e) {}
            long timeout = System.nanoTime() + TimeUnit.NANOSECONDS.convert(project.nimbledroid.ndGetProfileTimeout, TimeUnit.SECONDS)
            String errorMessage = null
            while (!done) {
                http.request(GET) { req ->
                    uri.path = uriPath
                    headers.'User-Agent' = "NimbleDroid Profiler Gradle Plugin/$nimbleVersion"
                    headers.'gradle' = nimbleVersion
                    response.success = { resp, reader ->
                        switch (reader.status) {
                            case ['Profiled', 'Failed']:
                                println reader.console_message
                                done = true
                                break
                            default:
                                if (timeout < System.nanoTime()) {
                                    println 'Profiling timed out'
                                    errorMessage = 'timeoutError'
                                    done = true
                                } else {
                                    println reader.console_message
                                }
                                break
                        }
                    }
                    response.failure = { resp ->
                        println 'There was a problem parsing the profile response.'
                        println 'You can contact support@nimbledroid.com if you need assistance.'
                        errorMessage = 'requestError'
                        done = true
                    }
                    response.'401' = { resp ->
                          println "Invalid API key, visit $project.nimbledroid.server/account to retrieve the current key."
                          println 'You can contact support@nimbledroid.com if you need assistance.'
                          errorMessage = 'invalidKeyError'
                          done = true
                    }
                }
                if (!done) {
                    sleep(30000)
                }
            }
            if (errorMessage) {
                ndFailure(errorMessage)
            }
            if (nimbleProperties.exists()) {
                nimbleProperties.delete()
            }
        }

        project.task('ndProfile') << {
            checkKey(project)
            project.ndUpload.execute()
            if (nimbleProperties.exists()) {
                project.ndGetProfile.execute()
            }
        }
    }

    void checkKey(Project project) {
        if (project.nimbledroid.apiKey == null) {
            println 'Must set nimbledroid.apiKey'
            ndFailure('nullKeyError')
        }
    }

    void ndFailure(String errorMessage) {
        if (nimbleProperties.exists()) {
            nimbleProperties.delete()
        }
        http.request(POST) { req ->
          uri.path = '/errors'
          requestContentType = APPLICATION_JSON
          body = [category: 'gradle', error: errorMessage]
          response.failure = {}
        }
        throw new StopActionException()
    }

    void greeting(Project project) {
        if (!greetingLock) {
            String service = project.nimbledroid.server
            try {
                URL url = new URL(service)
                service = url.getHost()
            } catch (MalformedURLException e) {}
            println "Running NimbleDroid Gradle Plugin v${nimbleVersion}, using service $service. For more info see $project.nimbledroid.server/help/ci"
            greetingLock = true
        }
    }
}
