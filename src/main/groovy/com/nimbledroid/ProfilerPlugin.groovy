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

class ProfilerPluginExtension {
    String apiKey
    String apkPath
}

class ProfilerPlugin implements Plugin<Project> {
    HTTPBuilder http
    File nimbleProperties

    void apply(Project project) {
        project.extensions.create("nimbledroid", ProfilerPluginExtension)

        http = new HTTPBuilder('https://staging.nimbledroid.com')
        nimbleProperties = project.file("${project.rootDir}/nimbledroid.properties")

        project.task('nimbleApps') << {
            http.auth.basic(project.nimbledroid.apiKey, "")
            http.request(GET) { req ->
                uri.path = '/api/v1/apps'
                response.success = { resp, reader ->
                    println reader
                }
            }
        }

        project.task('nimbleUpload') << {
            http.auth.basic(project.nimbledroid.apiKey, "")
            if(project.nimbledroid.apkPath == null) {
                String releaseApk = null
                project.android.applicationVariants.all { variant ->
                    if((variant.name).equals("release")) {
                        variant.outputs.each { output ->
                            releaseApk = output.outputFile
                        }
                    }
                }
                project.nimbledroid.apkPath = releaseApk
            }
            def apk = project.file(project.nimbledroid.apkPath)
            http.request(POST, JSON) { req ->
                uri.path = '/api/v1/apks'
                requestContentType = 'multipart/form-data'
                MultipartEntity entity = new MultipartEntity()
                entity.addPart('apk', new FileBody(apk))
                try {
                    String commitHash = "git rev-parse HEAD".execute().text.trim()
                    entity.addPart('commit', new StringBody(commitHash, TEXT_PLAIN));
                } catch(IOException e) {
                    println e.getMessage()
                }
                if(project.hasProperty('branch')) {
                    entity.addPart('branch', new StringBody("${project.branch}", TEXT_PLAIN));
                }
                if(project.hasProperty('flavor')) {
                    entity.addPart('flavor', new StringBody("${project.flavor}", TEXT_PLAIN));
                }
                req.entity = entity
                response.success = { resp, reader ->
                    println reader
                    nimbleProperties.write(reader.profile_url)
                }
            }
        }

        project.task('nimbleProfile') << {
            if(!nimbleProperties.exists()) {
                project.nimbleUpload.execute()
            }
            http.auth.basic(project.nimbledroid.apiKey, "")
            Boolean done = false
            String latestProfile = new String(nimbleProperties.readBytes())
            while(!done) {
                http.request(GET) { req ->
                    uri.path = "/api/v1$latestProfile"
                    response.success = { resp, reader ->
                        switch(reader.status) {
                            case "Profiled":
                                println reader.console_message
                                done = true
                                break
                            default:
                                println reader.console_message
                                break
                        }
                    }
                }
                if(!done) {
                    sleep(30000)
                }
            }
        }
    }
}
