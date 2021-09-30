import org.artifactory.repo.RepoPath
import org.artifactory.request.Request
import org.artifactory.repo.RepoPathFactory

download {
    beforeRemoteDownload { Request request, RepoPath repoPath ->
        try {

            if (repoPath.repoKey.endsWith("untrusted-curation-remote")) {
                def email = security.currentUser().email
                if (email == null || email.size() == 0) {
                    log.error("user doesn't have an email for curation: " + security.currentUser().username)
                } else {
                    def map = ["X-JFrog-ReplyTo": email]
                    headers = map
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e)
        }
    }
    beforeRemoteInfoRequest {Request request, RepoPath repoPath ->
        try {
            log.error("easter egg???")
            if (repoPath.repoKey.endsWith("untrusted-curation-remote")) {
                def email = security.currentUser().email
                if (email == null || email.size() == 0) {
                    log.error("user doesn't have an email for curation: " + security.currentUser().username)
                } else {
                    def map = ["X-JFrog-ReplyTo": email]
                    headers = map
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e)
        }}
}
