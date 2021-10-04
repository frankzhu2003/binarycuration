/*
* Copyright (C) 2014 JFrog Ltd.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
        * distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
        * limitations under the License.
*/

import groovy.json.JsonBuilder
import groovy.transform.Field
import org.apache.commons.lang.CharSet
import org.artifactory.repo.RepoPath
import org.artifactory.request.Request
import org.artifactory.repo.service.InternalRepositoryService
import org.artifactory.api.context.ContextHelper
import org.artifactory.request.RequestThreadLocal
import org.artifactory.repo.RepoPathFactory
import org.artifactory.exception.CancelException
import org.artifactory.resource.ResourceStreamHandle
import org.artifactory.storage.db.servers.model.ArtifactoryServer
import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService

import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import org.artifactory.util.HttpClientConfigurator
import org.apache.http.client.methods.*
import org.apache.http.entity.StringEntity

import groovy.json.JsonSlurper

// Allows to identify RT servers in the cluster.
// If more than one, will route handling of the webhooks to the servers[(path_of_artifact).hashcode() % num of servers]
@Field
ArtifactoryServersCommonService artifactoryServersCommonService = ctx.beanForType(ArtifactoryServersCommonService)

/* ASSUMPTION: a request is always registered here before Xray's webhook invokes the execution method!!! */
/*
  To avoid leaks and complexity in cleaning this list, we assume that if a request will trigger a webhook, it will happen
  before then next 50,000 requests complete.
  Yeah, <b>synchronizeMap</b> makes it slow, but shouldn't be critical and better than trying to fight ConcurrentHashMap with caching
  implementation.
 */
@Field Map<String, String> requests = Collections.synchronizedMap(new LinkedHashMap(50000, 0.75f, false) {
    @Override
    protected boolean removeEldestEntry(Map.Entry eldest) {
        return size() > 50000;
    }
})

def pkgType2XrayScheme(String pkgType) {
    switch (pkgType) {
        case "Maven": return "gav"
        case "Docker": return "docker"
        case "RPM": return "rpm"
        case "Debian": return "deb"
        case "NuGet": return "nuget"
        case "NPM": return "npm"
        case "Python": return "pip"
        case "Composer": return "composer"
        case "Golang": return "go"
        case "Alpine": return "alpine"
        default:
            log.error("unknown pkg_type: " + pkgType + ". going to use generic")
            return "generic" // TODO: this requires the sha256:FFFFFFFFF syntax for comp ID :(
    }
}

executions {

    routedRegisterRequest { params, ResourceStreamHandle body ->
        def payLoad = new JsonSlurper().parse(body.getInputStream())
        String path = payLoad.path
        String email = payLoad.email
        log.error("registering ROUTED: " + path + " by " + email)
        requests.put(path, email)
    }


    // expects: orgPath, approver, snIncNum,
    approveArtifact() { params, ResourceStreamHandle body ->
        def payLoad = new JsonSlurper().parse(body.getInputStream())
        def orgPath = payLoad.orgPath
        def approver = payLoad.approver
        def snIncNum = payLoad.snIncNum


        def repoPath = RepoPathFactory.create(orgPath.replaceFirst("-cache/", "/"))
        // TODO: get it without the cache!!!!
        repositories.setProperty(repoPath, "sn_approved_by", approver)
        repositories.setProperty(repoPath, "sn_incident_number", snIncNum)
        repositories.setProperty(repoPath, "sn_approve_note", "Approve reason is .... ")
        def targetRepo = repoPath.repoKey.replace("untrusted-remote", "curated")
        def destpath = RepoPathFactory.create(targetRepo, repoPath.path)
        repositories.copy(repoPath, destpath)

        log.error("Successfuly approved: " + orgPath)
    }
    createSNTicket() { params, ResourceStreamHandle body ->

        String message = org.apache.commons.io.IOUtils.toString(body.inputStream, Charset.defaultCharset())

        def xrayPayload = new JsonSlurper().parseText(message)

        /*
        Ok, assuming all issues are on the same artifact. Who knows what are the exceptions to that, however,
        going to report the first one with the highest severity and link SN back to xray
         */
        def firstArtObj = xrayPayload.issues[0].impacted_artifacts[0]
        String artPathTemp = firstArtObj.path + firstArtObj.name
        /* Now removing the "default/" prefix */
        String artPathCache = ((artPathTemp.startsWith("default/")) ? artPathTemp.substring(8) : artPathTemp)
        /* need the repo name without the "-cache" suffix to later-on assign the SN incident number as a property */
        String artPath = artPathCache.replaceFirst("-cache/", "/")


        List<ArtifactoryServer> allMembers = artifactoryServersCommonService.getAllArtifactoryServers()
        int numberOfServers = allMembers.size()
        ArtifactoryServer targetServer = null;
        int maxValue = Integer.MIN_VALUE
        allMembers.each {
            int temp = it.licenseKeyHash.hashCode() ^ artPath.hashCode()
            if (temp > maxValue) {
                maxValue = temp
                targetServer = it
            }
        }

        if (numberOfServers == 1 || artifactoryServersCommonService.getCurrentMember() == targetServer) {
            sendTOSN(xrayPayload.top_severity, firstArtObj.display_name, firstArtObj.pkg_type, artPathCache)
        } else {

            def targetURL = targetServer.contextUrl.replaceFirst(":8082", ":8081")
            if (!targetURL.startsWith("http")) {
                targetURL = "http://" + targetURL
            }

            StringBuilder url = new StringBuilder(targetURL)
            if (!targetServer.contextUrl.endsWith("/")) {
                url.append("/")
            }
            url.append("api/plugins/execute/routedCreateSNTicket")

            String reqBody = message
            def method3 = new HttpPost(url.toString())
            method3.addHeader("Content-Type", "application/json")
            method3.addHeader('Accepts', "application/json")
            method3.setEntity(new StringEntity(reqBody))
            def authVal = org.artifactory.request.RequestThreadLocal.context.get().requestThreadLocal.request.getHeader("authorization")


            method3.addHeader("authorization", authVal);
            def clientConf = new HttpClientConfigurator()
            clientConf.soTimeout(3000).connectionTimeout(3000)
            def client = clientConf.retry(0, false).client
            client.execute(method3)
        }




    }
    routedCreateSNTicket() { params, ResourceStreamHandle body ->
        def xrayPayload = new JsonSlurper().parse(body.inputStream)
        def firstArtObj = xrayPayload.issues[0].impacted_artifacts[0]
        String artPathTemp = firstArtObj.path + firstArtObj.name
        /* Now removing the "default/" prefix */
        String artPathCache = ((artPathTemp.startsWith("default/")) ? artPathTemp.substring(8) : artPathTemp)
        /* need the repo name without the "-cache" suffix to later-on assign the SN incident number as a property */
        String artPath = artPathCache.replaceFirst("-cache/", "/")

        sendTOSN(xrayPayload.top_severity, firstArtObj.display_name, firstArtObj.pkg_type, artPathCache)
    }
}

def sendTOSN(String maxSeverity, String displayName, String pkgType, String artPathCache) {
    String artPath = artPathCache.replaceFirst("-cache/", "/")
    if (!requests.containsKey(artPath)) {
        log.error("Don't have a registered request for: " + artPath);
        return;
    }

    String email = requests.get(artPath);
    requests.remove(artPath);
    def req = RequestThreadLocal.context.get()?.requestThreadLocal

    int pos = displayName.lastIndexOf(':')

    String xLink = req.baseUrl.replace("/artifactory",
            "") + "/ui/repos/tree/Xray/" + artPathCache
    def serviceNowURL = "https://YOUR_SERVICENOW_INSTANCE.service-now.com/api/YOUR_SERVICENOW_API_NUMBER/curationalert/create"
    def method3 = new HttpPost(serviceNowURL)
    method3.addHeader("Content-Type", "application/json")
    method3.addHeader('Accepts', "application/json")
    method3.setEntity(
            new StringEntity(
                    "{" +
                            "\"link\": \"" + xLink + "\"" +
                            ",\"user\": \"" + email + "\"" +
                            ",\"max_severity\": \"" + maxSeverity + "\"" +
                            ",\"artifact\": \"" + displayName + "\"" +
                            ",\"path\": \"" + artPathCache + "\"" +
                            ",\"pkgScheme\": \"" + pkgType2XrayScheme(pkgType) + "\"" +
                            "}"))

    def clientConf = new HttpClientConfigurator()
    clientConf.soTimeout(3000).connectionTimeout(3000)

    def client = clientConf.retry(0, false).client

    def serviceNowResp = client.execute(method3)
    def resp2 = new JsonSlurper().parse(new InputStreamReader(serviceNowResp.entity.content))

    serviceNowResp.close()
    repositories.setProperty(RepoPathFactory.create(artPath), "sn_incident_num", resp2.incident)
}

download {
    beforeDownloadRequest { Request request, RepoPath repoPath ->
        try {
            def req = RequestThreadLocal.context.get()?.requestThreadLocal
            def headerValue = req?.request?.getHeader('x-jfrog-replyto')
            if (headerValue != null) {
                String email = headerValue;
                String path = repoPath.toPath()

                // TODO: need to filter the active ones
                // also, assuming the list is not stable (results from DB query)
                List<ArtifactoryServer> allMembers = artifactoryServersCommonService.getAllArtifactoryServers()
                int numberOfServers = allMembers.size()
                ArtifactoryServer targetServer = null;
                int maxValue = Integer.MIN_VALUE
                allMembers.each {
                    int temp = it.licenseKeyHash.hashCode() ^ path.hashCode()
                    if (temp > maxValue) {
                        maxValue = temp
                        targetServer = it
                    }
                }

                if (numberOfServers == 1 || artifactoryServersCommonService.getCurrentMember() == targetServer) {
                    // No need to concern ourselves with HA routing
                    log.error("registering: " + path + " by " + email);
                    requests.put(path, email);
                } else {

                    def targetURL = targetServer.contextUrl.replaceFirst(":8082", ":8081")
                    if (!targetURL.startsWith("http")) {
                        targetURL = "http://" + targetURL
                    }

                    StringBuilder url = new StringBuilder(targetURL)
                    if (!targetServer.contextUrl.endsWith("/")) {
                        url.append("/")
                    }
                    url.append("api/plugins/execute/routedRegisterRequest")
                    log.error("url: " + url);
                    String reqBody = "{\"path\":\"" + path + "\", \"email\":\"" + email + "\"}"
                    def method3 = new HttpPost(url.toString())
                    method3.addHeader("Content-Type", "application/json")
                    method3.addHeader('Accepts', "application/json")
                    method3.setEntity(new StringEntity(reqBody))
                    def authVal = org.artifactory.request.RequestThreadLocal.context.get().requestThreadLocal.request.getHeader("authorization")


                    method3.addHeader("authorization", authVal);

                    def clientConf = new HttpClientConfigurator()
                    clientConf.soTimeout(3000).connectionTimeout(3000)
                    def client = clientConf.retry(0, false).client
                    client.execute(method3)
                }


            }
        } catch (Exception e) {

            log.error(e.getMessage(), e)
        }
    }


}
