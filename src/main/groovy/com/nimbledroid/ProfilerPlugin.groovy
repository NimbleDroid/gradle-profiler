package com.nimbledroid

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.POST
import static org.apache.http.entity.ContentType.TEXT_PLAIN
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.StopActionException

class ProfilerPluginExtension {
    String apiKey
    String variant = 'release'
    String apkName
}

class AppDataExtension {
    String username
    String password
}

class ProfilerPlugin implements Plugin<Project> {
    HTTPBuilder http
    File nimbleProperties

    void apply(Project project) {
        project.extensions.create("nimbledroid", ProfilerPluginExtension)
        project.nimbledroid.extensions.create("appData", AppDataExtension)

        http = new HTTPBuilder('https://staging.nimbledroid.com')
        nimbleProperties = project.file("${project.rootDir}/nimbledroid.properties")

        project.task('ndUpload') << {
            checkKey(project)
            http.auth.basic(project.nimbledroid.apiKey, "")
            String apkPath = null
            project.android.applicationVariants.all { variant ->
                if(variant.name == project.nimbledroid.variant) {
                    variant.outputs.each { output ->
                        if(project.nimbledroid.apkName != null) {
                            if (project.nimbledroid.apkName == output.getOutputFile().getName()) {
                                apkPath = output.outputFile
                            }
                        } else {
                                apkPath = output.outputFile
                        }
                    }
                }
            }
            if(apkPath == null) {
                println "No variant named ${project.nimbledroid.variant}"
                throw new StopActionException()
            }
            File apk = project.file(apkPath)
            if (!apk.exists()) {
                println "No apk exists for variant ${project.nimbledroid.variant}"
                throw new StopActionException()
            }
            http.request(POST, JSON) { req ->
                uri.path = '/api/v1/apks'
                requestContentType = 'multipart/form-data'
                MultipartEntity entity = new MultipartEntity()
                entity.addPart('apk', new FileBody(apk))
                try {
                    String commitHash = "git rev-parse HEAD".execute().text.trim()
                    if(!commitHash.startsWith("fatal")) {
                        entity.addPart('commit', new StringBody(commitHash, TEXT_PLAIN));
                    }
                } catch(IOException e) {
                }
                if(project.hasProperty('branch')) {
                    entity.addPart('branch', new StringBody("${project.branch}", TEXT_PLAIN));
                }
                if(project.hasProperty('flavor')) {
                    entity.addPart('flavor', new StringBody("${project.flavor}", TEXT_PLAIN));
                }
                if(project.nimbledroid.hasProperty('appData')) {
                    if (project.nimbledroid.appData.username != null) {
                        entity.addPart('auto_login', new StringBody("true", TEXT_PLAIN));
                        entity.addPart('username', new StringBody("{project.nimbledroid.appData.username}", TEXT_PLAIN));
                        entity.addPart('password', new StringBody("{project.nimbledroid.appData.password}", TEXT_PLAIN));
                    }
                }
                req.entity = entity
                response.success = { resp, reader ->
                    println "Profile URL: ${reader.profile_url}"
                    nimbleProperties.write(reader.profile_url)
                }
                response.failure = { resp ->
                    println 'There was a problem reaching the NimbleDroid service.'
                    println 'You can contact support@nimbledroid.com if you need assistance.'
                }
            }
        }

        project.task('ndGetProfile') << {
            checkKey(project)
            if(!nimbleProperties.exists()) {
                project.ndUpload.execute()
            }
            http.auth.basic(project.nimbledroid.apiKey, "")
            Boolean done = false
            String latestProfile = new String(nimbleProperties.readBytes()).trim()
            String uriPath = "/api/v1$latestProfile"
            try {
                URL url = new URL(latestProfile)
                uriPath = url.getPath()
            } catch(MalformedURLException e) {
            }
            while(!done) {
                http.request(GET) { req ->
                    uri.path = uriPath
                    response.success = { resp, reader ->
                        println reader.console_message
                        switch(reader.status) {
                            case ["Profiled", "Failed"]:
                                done = true
                                break
                            default:
                                break
                        }
                    }
                    response.failure = { resp ->
                        println 'There was a problem parsing the profile response.'
                        println 'You can contact support@nimbledroid.com if you need assistance.'
                        done = true
                    }
                }
                if(!done) {
                    sleep(30000)
                }
            }
        }

        project.task('ndProfile') << {
            checkKey(project)
            project.ndUpload.execute()
            project.ndGetProfile.execute()
        }
    }

    void checkKey(Project project) {
        if (project.nimbledroid.apiKey == null) {
            println 'Must set nimbledroid.apiKey'
            throw new StopActionException()
        }
    }
}
