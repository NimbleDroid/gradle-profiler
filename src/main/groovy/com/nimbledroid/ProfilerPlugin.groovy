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
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.StopActionException

class ProfilerPluginExtension {
    long ndGetProfileTimeout = 1800
    Boolean failBuildOnPluginError = false
    Boolean mappingUpload = true
    String apiKey = null
    String apkFilename = null
    String deviceConfig = null
    String mappingFilename = null
    String server = 'https://nimbledroid.com'
    String testApkFilename = null
    String uploadLabel = null
    String variant = 'release'

    void deviceConfig(String... devices) {
        deviceConfig = devices.join(',')
    }
}

class AppDataExtension {
    String username = null
    String password = null
}

class ProfilerPlugin implements Plugin<Project> {
    ProfilerPluginExtension nimbledroid = null
    HTTPBuilder http = null
    File nimbleProperties = null
    String nimbleVersion = null
    Boolean greetingLock = false

    void apply(Project project) {
        project.extensions.create('nimbledroid', ProfilerPluginExtension)
        nimbledroid = project.nimbledroid
        nimbledroid.extensions.create('appData', AppDataExtension)

        nimbleProperties = project.file("$project.rootDir/nimbledroid.properties")
        nimbleVersion = '1.1.4'

        project.task('ndUpload') {
            doLast {
                try {
                    greeting(project)
                    http = new HTTPBuilder(nimbledroid.server)
                    checkKey(project)
                    http.auth.basic(nimbledroid.apiKey, '')
                    Project rootProject = project.rootProject
                    String apkPath = null
                    File apk = null
                    File mapping = null
                    File testApk = null
                    Boolean explicitMapping = false
                    if (nimbledroid.apkFilename) {
                        apk = rootProject.file("app/build/outputs/apk/$nimbledroid.apkFilename")
                        if (!apk.exists()) {
                            if (!nimbledroid.apkFilename.contains('/')) {
                                println "Could not find apk ${apk.getAbsolutePath()}"
                                ndFailure('apkFilenameError')
                            } else {
                                apk = rootProject.file(nimbledroid.apkFilename)
                                if (!apk.exists()) {
                                    println "Could not find apk ${apk.getAbsolutePath()}"
                                    ndFailure('apkFilenameError')
                                }
                            }
                        }
                    } else if (project.hasProperty('android')) {
                        project.android.applicationVariants.all { variant ->
                            variant.outputs.each { output ->
                                if (variant.name == nimbledroid.variant) {
                                    apkPath = output.outputFile
                                    if (nimbledroid.mappingUpload) {
                                        mapping = variant.getMappingFile()
                                    }
                                }
                            }
                        }
                        if (apkPath == null) {
                            println "No variant named $nimbledroid.variant"
                            ndFailure('variantNameError')
                        }
                        apk = project.file(apkPath)
                        if (!apk.exists()) {
                            println "No apk exists for variant $nimbledroid.variant"
                            ndFailure('variantApkError')
                        }
                    } else {
                        println 'The NimbleDroid plugin requires either an android code block or the definition of an apkFilename in build.gradle.'
                        ndFailure('androidError')
                    }
                    if (nimbledroid.mappingUpload) {
                        if (nimbledroid.mappingFilename) {
                            explicitMapping = true
                            mapping = rootProject.file("app/build/outputs/mapping/release/$nimbledroid.mappingFilename")
                            if (!mapping.exists()) {
                                if (!nimbledroid.mappingFilename.contains('/')) {
                                    println "Could not find mapping ${mapping.getAbsolutePath()}"
                                    ndFailure('mappingError')
                                } else {
                                    mapping = rootProject.file(nimbledroid.mappingFilename)
                                    if (!mapping.exists()) {
                                        println "Could not find mapping ${mapping.getAbsolutePath()}"
                                        ndFailure('mappingError')
                                    }
                                }
                            }
                        }
                    }
                    if (nimbledroid.testApkFilename) {
                        testApk = rootProject.file("app/build/outputs/apk/$nimbledroid.testApkFilename")
                        if (!testApk.exists()) {
                            if (!nimbledroid.testApkFilename.contains('/')) {
                                println "Could not find test apk ${testApk.getAbsolutePath()}"
                                ndFailure('testApkFilenameError')
                            } else {
                                testApk = rootProject.file(nimbledroid.testApkFilename)
                                if (!testApk.exists()) {
                                    println "Could not find test apk ${testApk.getAbsolutePath()}"
                                    ndFailure('testApkFilenameError')
                                }
                            }
                        }
                    }
                    String errorMessage = null
                    http.request(POST, JSON) { req ->
                        uri.path = '/api/v2/apks'
                        headers.'User-Agent' = "NimbleDroid Profiler Gradle Plugin/$nimbleVersion"
                        headers.'gradle' = nimbleVersion
                        requestContentType = 'multipart/form-data'
                        MultipartEntity entity = new MultipartEntity()
                        entity.addPart('apk', new FileBody(apk))
                        if (mapping) {
                            entity.addPart('mapping', new FileBody(mapping))
                            entity.addPart('has_mapping', new StringBody('true'))
                            println "${explicitMapping ? 'mappingFilename set' : 'ProGuard enabled'} in build.gradle, uploading ProGuard mapping ${mapping.getAbsolutePath()}"
                        }
                        if (testApk) {
                            entity.addPart('test_apk', new FileBody(testApk))
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
                        if (nimbledroid.uploadLabel) {
                            entity.addPart('upload_label', new StringBody(nimbledroid.uploadLabel));
                        }
                        if (nimbledroid.deviceConfig) {
                            entity.addPart('device_config', new StringBody(nimbledroid.deviceConfig));
                        }
                        if (nimbledroid.hasProperty('appData')) {
                            if (nimbledroid.appData.username || nimbledroid.appData.password) {
                                entity.addPart('auto_login', new StringBody('true'));
                            }
                            if (nimbledroid.appData.username) {
                                entity.addPart('username', new StringBody(nimbledroid.appData.username));
                            }
                            if (nimbledroid.appData.password) {
                                entity.addPart('password', new StringBody(nimbledroid.appData.password));
                            }
                        }
                        req.entity = entity
                        response.success = { resp, reader ->
                            if (reader.console_message) {
                                println reader.console_message
                            }
                            if (reader.apk_url) {
                                println "Upload URL: $reader.apk_url"
                                nimbleProperties.write(reader.apk_url)
                                nimbleProperties.append('\n')
                            }
                        }
                        response.'401' = { resp ->
                              println "Invalid API key, visit $nimbledroid.server/account to retrieve the current key."
                              println 'You can contact support@nimbledroid.com if you need assistance.'
                              errorMessage = 'invalidKeyError'
                        }
                        response.failure = { resp ->
                            println "There was a problem reaching the NimbleDroid service ($nimbledroid.server$uri.path)."
                            println 'You can contact support@nimbledroid.com if you need assistance.'
                            errorMessage = 'requestError'
                        }
                    }
                    if (errorMessage) {
                        ndFailure(errorMessage)
                    }
                } catch (StopActionException e) {
                } catch (Exception e) {
                    println 'There was a problem with your request.'
                    println 'You can contact support@nimbledroid.com if you need assistance.'
                    ndFailure(e.getMessage())
                }
            }
        }

        project.task('ndGetProfile') {
            doLast {
                Boolean failBuildOnIssue = false
                try {
                    greeting(project)
                    http = new HTTPBuilder(nimbledroid.server)
                    checkKey(project)
                    if (!nimbleProperties.exists()) {
                        println "Couldn\'t find nimbledroid.properties file, ndUpload task was either not run or failed."
                        ndFailure('propertiesError')
                    }
                    http.auth.basic(nimbledroid.apiKey, '')
                    Boolean done = false
                    String latestProfile = new String(nimbleProperties.readBytes()).trim()
                    String uriPath
                    try {
                        URL url = new URL(latestProfile)
                        uriPath = url.getPath()
                    } catch (MalformedURLException e) {}
                    long timeout = System.nanoTime() + TimeUnit.NANOSECONDS.convert(nimbledroid.ndGetProfileTimeout, TimeUnit.SECONDS)
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
                                        if (reader.fail_build) {
                                            failBuildOnIssue = true
                                            throw new StopActionException()
                                        }
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
                                  println "Invalid API key, visit $nimbledroid.server/account to retrieve the current key."
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
                } catch (StopActionException e) {
                  if (failBuildOnIssue) {
                      throw new GradleException("NimbleDroid failing build because of detected issue(s).")
                  }
                } catch (Exception e) {
                    println 'There was a problem with your request.'
                    println 'You can contact support@nimbledroid.com if you need assistance.'
                    ndFailure(e.getMessage())
                }
            }
        }

        project.task('ndProfile') {
            doLast {
                checkKey(project)
                project.ndUpload.execute()
                if (nimbleProperties.exists()) {
                    project.ndGetProfile.execute()
                }
            }
        }
    }

    void checkKey(Project project) {
        if (nimbledroid.apiKey == null) {
            println 'Must set nimbledroid.apiKey'
            ndFailure('nullKeyError')
        }
    }

    void ndFailure(String errorMessage) {
        try {
            if (nimbleProperties.exists()) {
                nimbleProperties.delete()
            }
            if (http) {
                http.request(POST) { req ->
                  uri.path = '/errors'
                  requestContentType = APPLICATION_JSON
                  body = [category: 'gradle', error: errorMessage]
                  response.failure = {}
                }
            }
        } catch (Exception e) {}
        if (nimbledroid.failBuildOnPluginError) {
            throw new GradleException("failBuildOnPluginError is set, NimbleDroid failing build because of plugin error.")
        } else {
            throw new StopActionException()
        }
    }

    void greeting(Project project) {
        if (!greetingLock) {
            String service = nimbledroid.server
            try {
                URL url = new URL(service)
                service = url.getHost()
            } catch (MalformedURLException e) {}
            println "Running NimbleDroid Gradle Plugin v${nimbleVersion}, using service $service. For more info see $nimbledroid.server/help/ci"
            greetingLock = true
        }
    }
}
