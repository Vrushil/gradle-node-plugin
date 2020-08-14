package com.moowork.gradle.node.task

import com.moowork.gradle.node.NodeExtension
import com.moowork.gradle.node.NodePlugin
import com.moowork.gradle.node.util.BackwardsCompat
import com.moowork.gradle.node.variant.Variant
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SetupTask
    extends DefaultTask
{
    public final static String NAME = 'nodeSetup'

    private Provider<NodeExtension> config

    SetupTask()
    {
        this.config = project.provider({ NodeExtension.get( this.project ) })
        this.group = NodePlugin.NODE_GROUP
        this.description = 'Download and install a local node/npm version.'
        this.enabled = false
    }

    @Input
    public Set<String> getInput()
    {
        def set = new HashSet<>()
        set.add( this.config.get().download )
        set.add( this.config.get().variant.archiveDependency )
        set.add( this.config.get().variant.exeDependency )
        return set
    }

    @OutputDirectory
    public File getNodeDir()
    {
        return this.config.get().variant.nodeDir
    }

    @TaskAction
    void exec()
    {
        addRepositoryIfNeeded()

        if ( this.config.get().variant.exeDependency )
        {
            copyNodeExe()
        }

        deleteExistingNode()
        unpackNodeArchive()
        setExecutableFlag()
    }

    private void copyNodeExe()
    {
        this.project.copy {
            from getNodeExeFile()
            into this.config.get().variant.nodeBinDir
            rename 'node.+\\.exe', 'node.exe'
        }
    }

    private void deleteExistingNode()
    {
        this.project.delete( getNodeDir().parent )
    }

    private void unpackNodeArchive()
    {
        if ( getNodeArchiveFile().getName().endsWith( 'zip' ) )
        {
            this.project.copy {
                from this.project.zipTree( getNodeArchiveFile() )
                into getNodeDir().parent
            }
        }
        else if ( this.config.get().variant.exeDependency )
        {
            //Remap lib/node_modules to node_modules (the same directory as node.exe) because that's how the zip dist does it
            this.project.copy {
                from this.project.tarTree( getNodeArchiveFile() )
                into this.config.get().variant.nodeBinDir
                eachFile {
                    def m = it.path =~ /^.*?[\\/]lib[\\/](node_modules.*$)/
                    if ( m.matches() )
                    {
                        // remap the file to the root
                        it.path = m.group( 1 )
                    }
                    else
                    {
                        it.exclude()
                    }
                }
                includeEmptyDirs = false
            }
        }
        else
        {
            this.project.copy {
                from this.project.tarTree( getNodeArchiveFile() )
                into getNodeDir().parent
            }

            def variant = config.get().variant
            // Fix broken symlink
            Path npm = Paths.get( variant.nodeBinDir.path, 'npm' )
            if ( Files.deleteIfExists( npm ) )
            {
                Files.createSymbolicLink( npm,
                        variant.nodeBinDir.toPath().relativize(Paths.get(variant.npmScriptFile)) )
            }
            Path npx = Paths.get( variant.nodeBinDir.path, 'npx' )
            if ( Files.deleteIfExists( npx ) )
            {
                Files.createSymbolicLink( npx,
                        variant.nodeBinDir.toPath().relativize(Paths.get(variant.npxScriptFile)) )
            }
        }
    }

    private void setExecutableFlag()
    {
        if ( !this.config.get().variant.windows )
        {
            new File( this.config.get().variant.nodeExec ).setExecutable( true )
        }
    }

    @Internal
    protected File getNodeExeFile()
    {
        return resolveSingle( this.config.get().variant.exeDependency )
    }

    @Internal
    protected File getNodeArchiveFile()
    {
        return resolveSingle( this.config.get().variant.archiveDependency )
    }

    private File resolveSingle( String name )
    {
        def dep = this.project.dependencies.create( name )
        def conf = this.project.configurations.detachedConfiguration( dep )
        conf.transitive = false
        return conf.resolve().iterator().next();
    }

    private void addRepositoryIfNeeded() {
        if ( this.config.get().distBaseUrl != null ) {
            addRepository this.config.get().distBaseUrl
        }
    }

    private void addRepository( String distUrl ) {
        this.project.repositories.ivy {
            url distUrl
            if (BackwardsCompat.usePatternLayout()) {
                patternLayout {
                    artifact 'v[revision]/[artifact](-v[revision]-[classifier]).[ext]'
                    ivy 'v[revision]/ivy.xml'
                }
            } else {
                layout 'pattern', {
                    artifact 'v[revision]/[artifact](-v[revision]-[classifier]).[ext]'
                    ivy 'v[revision]/ivy.xml'
                }
            }
            if (BackwardsCompat.useMetadataSourcesRepository()) {
                metadataSources {
                    artifact()
                }
            }
        }
    }
}
