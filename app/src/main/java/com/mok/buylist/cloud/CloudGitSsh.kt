package com.mok.buylist.cloud

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.IOException

/** Reuse the pinned JSch connection; never delegate to device shell or user SSH configuration. */
internal object CloudGitSsh {
    const val REMOTE = "ssh://git@ssh.github.com:443/minemax777702/app-cloud.git"

    val configure = TransportConfigCallback { transport ->
        require(transport is SshTransport)
        transport.sshSessionFactory = Factory
    }

    private object Factory : SshSessionFactory() {
        override fun getType() = "buylist-android-keystore"
        override fun getSession(uri: URIish, credentialsProvider: CredentialsProvider?, fs: FS, tms: Int): RemoteSession {
            require(uri.scheme == "ssh" && uri.host == "ssh.github.com" && uri.port == 443 &&
                uri.user == "git" && uri.path == "/minemax777702/app-cloud.git")
            return Connection(CloudSshProbe.openSession(443, CloudDeviceIdentity.keyPair()))
        }
    }

    private class Connection(private val session: Session) : RemoteSession {
        override fun exec(commandName: String, timeout: Int): Process {
            val allowed = listOf("git-upload-pack", "git-receive-pack").any { verb ->
                commandName == "$verb '/minemax777702/app-cloud.git'" ||
                    commandName == "$verb 'minemax777702/app-cloud.git'"
            }
            if (!allowed) throw IOException("Unexpected Git SSH command")
            val channel = session.openChannel("exec") as ChannelExec
            try {
                channel.setCommand(commandName)
                val input = channel.inputStream
                val output = channel.outputStream
                val error = channel.errStream
                channel.connect((timeout.coerceIn(1, 30)) * 1000)
                return object : Process() {
                    override fun getInputStream() = input
                    override fun getOutputStream() = output
                    override fun getErrorStream() = error
                    override fun waitFor(): Int {
                        while (!channel.isClosed) Thread.sleep(25)
                        return channel.exitStatus
                    }
                    override fun exitValue(): Int {
                        if (!channel.isClosed) throw IllegalThreadStateException()
                        return channel.exitStatus
                    }
                    override fun destroy() { channel.disconnect() }
                }
            } catch (e: Exception) {
                channel.disconnect()
                throw IOException("Git SSH channel could not be opened", e)
            }
        }
        override fun disconnect() { session.disconnect() }
    }
}
