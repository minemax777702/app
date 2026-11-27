package com.mok.buylist.cloud

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * One fast-forward-only Git branch per space/epoch/device. Git is untrusted storage:
 * callers must verify the manifest, signature, role and key before accepting events.
 * Never checks out remote files or uses the source repository.
 */
internal interface CloudEventTransport {
    fun append(event: CloudSignedEvent)
    fun readAfter(space: CloudSpace, epoch: Long, device: String, cursor: CloudCursor): List<CloudSignedEvent>
}

internal class CloudGitEvents(
    private val repository: Repository,
    private val remote: String,
    private val configure: TransportConfigCallback? = null,
) : CloudEventTransport {
    init { require(repository.isBare) }
    private val git = Git(repository)

    private fun branch(space: CloudSpace, epoch: Long, device: String): String {
        require(epoch > 0 && device.matches(Regex("[0-9a-f]{64}")))
        return "refs/heads/cloud-v1/${space.key.replace(':', '/')}/$epoch/$device"
    }

    private fun tracking(branch: String) = branch.replace("refs/heads/", "refs/remotes/buylist/")

    private fun fetchTip(branch: String): ObjectId? {
        val list = git.lsRemote().setRemote(remote).setHeads(true).setTags(false).setTimeout(30)
        configure?.let { list.setTransportConfigCallback(it) }
        if (list.call().none { it.name == branch }) return null
        val fetch = git.fetch().setRemote(remote).setRefSpecs(RefSpec("+$branch:${tracking(branch)}"))
            .setTagOpt(TagOpt.NO_TAGS).setTimeout(30)
        configure?.let { fetch.setTransportConfigCallback(it) }
        fetch.call()
        return requireNotNull(repository.exactRef(tracking(branch))?.objectId) { "Remote tip unavailable" }
    }

    private fun read(commit: RevCommit): CloudSignedEvent {
        TreeWalk(repository).use { tree ->
            tree.addTree(commit.tree)
            require(tree.next() && tree.pathString == "event.bin" && tree.getFileMode(0) == FileMode.REGULAR_FILE)
            val bytes = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes(CloudSignedEvent.MAX_DELTA_BYTES + 1024)
            require(!tree.next()) { "Unexpected event tree" }
            return CloudSignedEvent.decode(bytes)
        }
    }

    private fun requireScope(event: CloudSignedEvent, space: CloudSpace, epoch: Long, device: String) {
        require(event.header.space == space && event.header.epoch == epoch && event.header.deviceId == device)
    }

    /** Returns oldest first; no gap, divergent chain or missing acknowledged cursor may be ignored. */
    override fun readAfter(space: CloudSpace, epoch: Long, device: String, cursor: CloudCursor): List<CloudSignedEvent> = synchronized(LOCK) {
        val tip = fetchTip(branch(space, epoch, device))
        if (tip == null) {
            require(cursor.sequence == 0L) { "Remote event history disappeared" }
            return@synchronized emptyList()
        }
        RevWalk(repository).use { walk ->
            val result = ArrayList<CloudSignedEvent>()
            var commit = walk.parseCommit(tip)
            var child: CloudSignedEvent? = null
            while (true) {
                val event = read(commit)
                requireScope(event, space, epoch, device)
                child?.let { require(it.header.sequence == event.header.sequence + 1 && it.header.previousHash == event.digest) }
                require(event.header.sequence >= cursor.sequence)
                if (event.header.sequence == cursor.sequence) {
                    require(event.digest == cursor.digest) { "Acknowledged event changed" }
                    break
                }
                require(result.size < MAX_BATCH) { "Event batch exceeds limit; checkpoint required" }
                result.add(event)
                if (event.header.sequence == 1L) {
                    require(cursor.sequence == 0L && commit.parentCount == 0 && event.header.previousHash.isEmpty())
                    break
                }
                require(commit.parentCount == 1) { "Missing event history" }
                child = event
                commit = walk.parseCommit(commit.getParent(0))
            }
            result.asReversed().toList()
        }
    }

    /**
     * Retry with the exact same signed bytes. Remote rejection or a conflicting tip is never
     * resolved by force-push. A durable outbox must persist bytes BEFORE calling this method.
     */
    override fun append(event: CloudSignedEvent): Unit = synchronized(LOCK) {
        val header = event.header
        val branch = branch(header.space, header.epoch, header.deviceId)
        val tip = fetchTip(branch)
        if (tip != null) RevWalk(repository).use { walk ->
            val last = read(walk.parseCommit(tip))
            requireScope(last, header.space, header.epoch, header.deviceId)
            if (last.digest == event.digest) return@synchronized
            require(header.sequence == last.header.sequence + 1 && header.previousHash == last.digest) {
                "Remote event chain changed; reload before publishing"
            }
        } else require(header.sequence == 1L && header.previousHash.isEmpty())
        val id = repository.newObjectInserter().use { inserter ->
            val blob = inserter.insert(Constants.OBJ_BLOB, event.encode())
            val tree = TreeFormatter().apply { append("event.bin", FileMode.REGULAR_FILE, blob) }
            val treeId = inserter.insert(tree)
            val author = PersonIdent("BuyList device", "device@buylist.invalid")
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                setAuthor(author)
                setCommitter(author)
                if (tip != null) setParentId(tip)
                message = "Signed event ${header.sequence}"
            }
            inserter.insert(commit).also { inserter.flush() }
        }
        val local = branch.replace("refs/heads/", "refs/heads/outbox/")
        val update = repository.updateRef(local)
        update.setNewObjectId(id)
        update.setForceUpdate(true) // local staging ref only; remote push is never forced.
        val updated = update.update()
        require(updated in setOf(org.eclipse.jgit.lib.RefUpdate.Result.NEW, org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD,
            org.eclipse.jgit.lib.RefUpdate.Result.FORCED, org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE))
        val push = git.push().setRemote(remote).setRefSpecs(RefSpec("$local:$branch")).setForce(false).setTimeout(30)
        configure?.let { push.setTransportConfigCallback(it) }
        val statuses = push.call().flatMap { it.remoteUpdates }.filter { it.remoteName == branch }
        require(statuses.size == 1 && statuses.single().status in setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE)) {
            "Git did not confirm the event; preserve outbox and retry"
        }
    }

    private companion object {
        val LOCK = Any()
        const val MAX_BATCH = 500
    }
}
