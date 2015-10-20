package com.nimbledroid

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.POST
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
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
            def apk = project.file(project.nimbledroid.apkPath)
            http.request(POST, JSON) { req ->
                uri.path = '/api/v1/apks'
                requestContentType = 'multipart/form-data'
                MultipartEntity entity = new MultipartEntity()
                entity.addPart("apk", new FileBody(apk))
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
                                println reader
                                def num_new_issues = reader.scenarios[0].num_new_issues
                                switch(num_new_issues) {
                                    case 0:
                                        println "Passed, no new issues"
                                        break
                                    default:
                                        println "Failed, $num_new_issues new issues"
                                        break
                                }
                                done = true
                                break
                            default:
                                println reader
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
