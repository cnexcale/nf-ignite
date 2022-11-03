/*
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.executor
import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.file.FileHelper
import nextflow.processor.TaskBean

import java.nio.file.Paths

/**
 * Implements file staging strategy for a Ignite task.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class IgFileStagingStrategy implements StagingStrategy {

    /**
     * Task meta-data
     */
    protected TaskBean task

    /**
     * Session unique-id
     */
    protected UUID sessionId

    /**
     * The local scratch dir where the task is actually executed in the remote node.
     * Note: is declared transient because it is valid only on the remote-side,
     * thus it do not need to be transported
     *
     */
    private Path _localWorkDir

    protected Path getLocalWorkDir() { _localWorkDir }

    /**
     *
     */
    protected Path localStorageRoot


    /**
     * A temporary where all files are cached. The folder is deleted during instance shut-down
     */
    private static final Path _localCacheDir = FileHelper.createLocalDir()

    Path getLocalCacheDir() { resolveLocalCacheDir(_localCacheDir, localStorageRoot) }

    protected List<Path> cachedFiles;

    IgFileStagingStrategy( TaskBean task, UUID sessionId, Map config ) {
        this.task = task
        this.sessionId = sessionId
        this.localStorageRoot = getLocalStorageRoot(config)
        localCacheDir.deleteOnExit()
        _localWorkDir = createLocalDir(localStorageRoot)
        cachedFiles = []
    }

    /**
     * Copies to the task input files to the execution folder, that is {@link #_localWorkDir}
     * folder created when this method is invoked
     */
    @Override
    void stage() {

        // create a local work dir
        _localWorkDir = createLocalDir(localStorageRoot)

        log?.debug "Task ${task.name} > using workdDir ${_localWorkDir} and cache dir ${localCacheDir?.toString()}"

        if( !task.inputFiles )
            return

        // move the input files there
        for( Map.Entry<String,Path> entry : task.inputFiles.entrySet() ) {
            final fileName = entry.key
            final source = entry.value
            final cached = FileHelper.getLocalCachePath(source, localCacheDir, sessionId)
            cachedFiles.add(cached)
            final staged = localWorkDir.resolve(fileName)
            // create any sub-directory before create the symlink
            if( fileName.contains('/') ) {
                Files.createDirectories(staged.parent)
            }
            log?.debug "Task ${task.name} > staging path: '${source}' to: '$staged'"
            Files.createSymbolicLink(staged, cached)
        }

    }

    /**
     * Copy back the task output files from the execution directory in the local node storage
     * to the task {@link nextflow.processor.TaskRun#getTargetDir()}
     */
    @Override
    void unstage() {

        log?.debug "Unstaging file names: $task.outputFiles"

        if( !task.outputFiles )
            return

        // create a bash script that will copy the out file to the working directory
        if( !Files.exists(task.targetDir) )
            Files.createDirectories(task.targetDir)

        for( String name : task.outputFiles ) {
            try {
                copyToTargetDir(name, localWorkDir, task.targetDir)
            }
            catch( IOException e ) {
                log?.error("Unable to copy result file: $name to target dir", e)
            }
        }
    }

    void cleanupCache() {
        if ( cachedFiles.size() <= 0 ) return

        log?.debug( "Cleaning up ${ cachedFiles.size() } files in local cache" )
        cachedFiles.forEach(it -> {
            if ( !it.delete() )
                log?.warn( "Could not delete ${it.toString()} from local cache" )
        })
        log?.debug( "Local cache cleanup done" )
        cachedFiles = []
    }

    /**
     * Copy the file with the specified name from the task execution folder
     * to the {@code targetDir}
     *
     * @param filePattern A file name relative to the {@link #_localWorkDir}.
     *        It can contain globs wildcards
     */
    @PackageScope
    void copyToTargetDir( String filePattern, Path from, Path to ) {

        def type = filePattern.contains('**') ? 'file' : 'any'

        FileHelper.visitFiles( from, filePattern, type: type ) { Path it ->
            // note: converts to a *string* other the two paths may belong to two different systems and thus throwing an exception
            final rel = from.relativize(it).toString()
            it.copyTo(to.resolve(rel))
        }
    }

    private static Path getLocalStorageRoot(Map config) {
        def localStorageRoot = (String) ((Map)config?.get("cluster"))?.get("localStorageRoot")
        return localStorageRoot
                ? Paths.get(localStorageRoot)
                : null
    }

    private static Path createLocalDir(Path basePath) {
        return basePath
                ? FileHelper.createTempFolder(basePath)
                : FileHelper.createLocalDir()
    }

    private static Path resolveLocalCacheDir(Path localCacheDir, Path localStorageRoot) {
        if ( !localStorageRoot )
            localCacheDir
        else{
            def localCacheStr = localCacheDir.toAbsolutePath().toString()
            Paths.get(localStorageRoot.toString(), "cache", localCacheStr[1..localCacheStr.length() - 1])
        }
    }
}
