package org.apache.archiva.webdav;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.admin.model.RepositoryAdminException;
import org.apache.archiva.audit.AuditEvent;
import org.apache.archiva.audit.AuditListener;
import org.apache.archiva.audit.Auditable;
import org.apache.archiva.common.plexusbridge.PlexusSisuBridge;
import org.apache.archiva.common.plexusbridge.PlexusSisuBridgeException;
import org.apache.archiva.common.utils.PathUtil;
import org.apache.archiva.common.utils.VersionUtil;
import org.apache.archiva.configuration.ArchivaConfiguration;
import org.apache.archiva.configuration.RepositoryGroupConfiguration;
import org.apache.archiva.indexer.merger.IndexMerger;
import org.apache.archiva.indexer.merger.IndexMergerException;
import org.apache.archiva.indexer.merger.TemporaryGroupIndex;
import org.apache.archiva.indexer.search.RepositorySearch;
import org.apache.archiva.maven2.metadata.MavenMetadataReader;
import org.apache.archiva.model.ArchivaRepositoryMetadata;
import org.apache.archiva.model.ArtifactReference;
import org.apache.archiva.policies.ProxyDownloadException;
import org.apache.archiva.proxy.RepositoryProxyConnectors;
import org.apache.archiva.redback.authentication.AuthenticationException;
import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.apache.archiva.redback.authorization.AuthorizationException;
import org.apache.archiva.redback.authorization.UnauthorizedException;
import org.apache.archiva.redback.integration.filter.authentication.HttpAuthenticator;
import org.apache.archiva.redback.policy.AccountLockedException;
import org.apache.archiva.redback.policy.MustChangePasswordException;
import org.apache.archiva.redback.system.SecuritySession;
import org.apache.archiva.redback.users.User;
import org.apache.archiva.redback.users.UserManager;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.RepositoryContentFactory;
import org.apache.archiva.repository.RepositoryException;
import org.apache.archiva.repository.RepositoryNotFoundException;
import org.apache.archiva.repository.content.LegacyPathParser;
import org.apache.archiva.repository.content.RepositoryRequest;
import org.apache.archiva.repository.layout.LayoutException;
import org.apache.archiva.repository.metadata.MetadataTools;
import org.apache.archiva.repository.metadata.RepositoryMetadataException;
import org.apache.archiva.repository.metadata.RepositoryMetadataMerge;
import org.apache.archiva.repository.metadata.RepositoryMetadataWriter;
import org.apache.archiva.scheduler.repository.RepositoryArchivaTaskScheduler;
import org.apache.archiva.security.ServletAuthenticator;
import org.apache.archiva.webdav.util.MimeTypes;
import org.apache.archiva.webdav.util.RepositoryPathUtil;
import org.apache.archiva.webdav.util.TemporaryGroupIndexSessionCleaner;
import org.apache.archiva.webdav.util.WebdavMethodUtil;
import org.apache.archiva.xml.XMLException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.DavResource;
import org.apache.jackrabbit.webdav.DavResourceFactory;
import org.apache.jackrabbit.webdav.DavResourceLocator;
import org.apache.jackrabbit.webdav.DavServletRequest;
import org.apache.jackrabbit.webdav.DavServletResponse;
import org.apache.jackrabbit.webdav.DavSession;
import org.apache.jackrabbit.webdav.lock.LockManager;
import org.apache.jackrabbit.webdav.lock.SimpleLockManager;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Relocation;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.digest.ChecksumFile;
import org.codehaus.plexus.digest.Digester;
import org.codehaus.plexus.digest.DigesterException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
@Service ( "davResourceFactory#archiva" )
public class ArchivaDavResourceFactory
    implements DavResourceFactory, Auditable
{
    private static final String PROXIED_SUFFIX = " (proxied)";

    private static final String HTTP_PUT_METHOD = "PUT";

    private static final MavenXpp3Reader MAVEN_XPP_3_READER = new MavenXpp3Reader();

    private Logger log = LoggerFactory.getLogger( ArchivaDavResourceFactory.class );

    /**
     *
     */
    @Inject
    private List<AuditListener> auditListeners = new ArrayList<AuditListener>();

    /**
     *
     */
    @Inject
    private RepositoryContentFactory repositoryFactory;

    /**
     *
     */
    private RepositoryRequest repositoryRequest;

    /**
     *
     */
    @Inject
    @Named ( value = "repositoryProxyConnectors#default" )
    private RepositoryProxyConnectors connectors;

    /**
     *
     */
    @Inject
    private MetadataTools metadataTools;

    /**
     *
     */
    @Inject
    private MimeTypes mimeTypes;

    /**
     *
     */
    private ArchivaConfiguration archivaConfiguration;

    /**
     *
     */
    @Inject
    private ServletAuthenticator servletAuth;

    /**
     *
     */
    @Inject
    @Named ( value = "httpAuthenticator#basic" )
    private HttpAuthenticator httpAuth;

    @Inject
    private IndexMerger indexMerger;

    @Inject
    private RepositorySearch repositorySearch;

    /**
     * Lock Manager - use simple implementation from JackRabbit
     */
    private final LockManager lockManager = new SimpleLockManager();

    /**
     *
     */
    private ChecksumFile checksum;

    /**
     *
     */
    private Digester digestSha1;

    /**
     *
     */
    private Digester digestMd5;

    /**
     *
     */
    @Inject
    @Named ( value = "archivaTaskScheduler#repository" )
    private RepositoryArchivaTaskScheduler scheduler;

    private ApplicationContext applicationContext;

    @Inject
    public ArchivaDavResourceFactory( ApplicationContext applicationContext, PlexusSisuBridge plexusSisuBridge,
                                      ArchivaConfiguration archivaConfiguration )
        throws PlexusSisuBridgeException
    {
        this.archivaConfiguration = archivaConfiguration;
        this.applicationContext = applicationContext;
        this.checksum = plexusSisuBridge.lookup( ChecksumFile.class );

        this.digestMd5 = plexusSisuBridge.lookup( Digester.class, "md5" );
        this.digestSha1 = plexusSisuBridge.lookup( Digester.class, "sha1" );

        repositoryRequest = new RepositoryRequest( new LegacyPathParser( archivaConfiguration ) );
    }

    @PostConstruct
    public void initialize()
    {

    }

    public DavResource createResource( final DavResourceLocator locator, final DavServletRequest request,
                                       final DavServletResponse response )
        throws DavException
    {
        ArchivaDavResourceLocator archivaLocator = checkLocatorIsInstanceOfRepositoryLocator( locator );

        RepositoryGroupConfiguration repoGroupConfig =
            archivaConfiguration.getConfiguration().getRepositoryGroupsAsMap().get( archivaLocator.getRepositoryId() );

        String activePrincipal = getActivePrincipal( request );

        List<String> resourcesInAbsolutePath = new ArrayList<String>();

        boolean readMethod = WebdavMethodUtil.isReadMethod( request.getMethod() );
        DavResource resource;
        if ( repoGroupConfig != null )
        {
            if ( !readMethod )
            {
                throw new DavException( HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                                        "Write method not allowed for repository groups." );
            }

            log.debug( "Repository group '{}' accessed by '{}", repoGroupConfig.getId(), activePrincipal );

            // handle browse requests for virtual repos
            if ( RepositoryPathUtil.getLogicalResource( archivaLocator.getOrigResourcePath() ).endsWith( "/" ) )
            {
                return getResource( request, repoGroupConfig.getRepositories(), archivaLocator,
                                    archivaLocator.getRepositoryId() );
            }
            else
            {
                // make a copy to avoid potential concurrent modifications (eg. by configuration)
                // TODO: ultimately, locking might be more efficient than copying in this fashion since updates are
                //  infrequent
                List<String> repositories = new ArrayList<String>( repoGroupConfig.getRepositories() );
                resource = processRepositoryGroup( request, archivaLocator, repositories, activePrincipal,
                                                   resourcesInAbsolutePath, archivaLocator.getRepositoryId() );
            }
        }
        else
        {
            ManagedRepositoryContent managedRepository = null;

            try
            {
                managedRepository = repositoryFactory.getManagedRepositoryContent( archivaLocator.getRepositoryId() );
            }
            catch ( RepositoryNotFoundException e )
            {
                throw new DavException( HttpServletResponse.SC_NOT_FOUND,
                                        "Invalid repository: " + archivaLocator.getRepositoryId() );
            }
            catch ( RepositoryException e )
            {
                throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e );
            }

            log.debug( "Managed repository '{}' accessed by '{}'", managedRepository.getId(), activePrincipal );

            resource = processRepository( request, archivaLocator, activePrincipal, managedRepository );

            String logicalResource = RepositoryPathUtil.getLogicalResource( locator.getResourcePath() );
            resourcesInAbsolutePath.add(
                new File( managedRepository.getRepoRoot(), logicalResource ).getAbsolutePath() );
        }

        String requestedResource = request.getRequestURI();

        // MRM-872 : merge all available metadata
        // merge metadata only when requested via the repo group
        if ( ( repositoryRequest.isMetadata( requestedResource ) || repositoryRequest.isMetadataSupportFile(
            requestedResource ) ) && repoGroupConfig != null )
        {
            // this should only be at the project level not version level!
            if ( isProjectReference( requestedResource ) )
            {
                String artifactId = StringUtils.substringBeforeLast( requestedResource.replace( '\\', '/' ), "/" );
                artifactId = StringUtils.substringAfterLast( artifactId, "/" );

                ArchivaDavResource res = (ArchivaDavResource) resource;
                String filePath =
                    StringUtils.substringBeforeLast( res.getLocalResource().getAbsolutePath().replace( '\\', '/' ),
                                                     "/" );
                filePath = filePath + "/maven-metadata-" + repoGroupConfig.getId() + ".xml";

                // for MRM-872 handle checksums of the merged metadata files
                if ( repositoryRequest.isSupportFile( requestedResource ) )
                {
                    File metadataChecksum =
                        new File( filePath + "." + StringUtils.substringAfterLast( requestedResource, "." ) );
                    if ( metadataChecksum.exists() )
                    {
                        LogicalResource logicalResource =
                            new LogicalResource( RepositoryPathUtil.getLogicalResource( locator.getResourcePath() ) );

                        resource =
                            new ArchivaDavResource( metadataChecksum.getAbsolutePath(), logicalResource.getPath(), null,
                                                    request.getRemoteAddr(), activePrincipal, request.getDavSession(),
                                                    archivaLocator, this, mimeTypes, auditListeners, scheduler );
                    }
                }
                else
                {
                    if ( resourcesInAbsolutePath != null && resourcesInAbsolutePath.size() > 1 )
                    {
                        // merge the metadata of all repos under group
                        ArchivaRepositoryMetadata mergedMetadata = new ArchivaRepositoryMetadata();
                        for ( String resourceAbsPath : resourcesInAbsolutePath )
                        {
                            try
                            {
                                File metadataFile = new File( resourceAbsPath );
                                ArchivaRepositoryMetadata repoMetadata = MavenMetadataReader.read( metadataFile );
                                mergedMetadata = RepositoryMetadataMerge.merge( mergedMetadata, repoMetadata );
                            }
                            catch ( XMLException e )
                            {
                                throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                        "Error occurred while reading metadata file." );
                            }
                            catch ( RepositoryMetadataException r )
                            {
                                throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                        "Error occurred while merging metadata file." );
                            }
                        }

                        try
                        {
                            File resourceFile = writeMergedMetadataToFile( mergedMetadata, filePath );

                            LogicalResource logicalResource = new LogicalResource(
                                RepositoryPathUtil.getLogicalResource( locator.getResourcePath() ) );

                            resource =
                                new ArchivaDavResource( resourceFile.getAbsolutePath(), logicalResource.getPath(), null,
                                                        request.getRemoteAddr(), activePrincipal,
                                                        request.getDavSession(), archivaLocator, this, mimeTypes,
                                                        auditListeners, scheduler );
                        }
                        catch ( RepositoryMetadataException r )
                        {
                            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                    "Error occurred while writing metadata file." );
                        }
                        catch ( IOException ie )
                        {
                            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                    "Error occurred while generating checksum files." );
                        }
                        catch ( DigesterException de )
                        {
                            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                    "Error occurred while generating checksum files." );
                        }
                    }
                }
            }
        }

        setHeaders( response, locator, resource );

        // compatibility with MRM-440 to ensure browsing the repository works ok
        if ( resource.isCollection() && !request.getRequestURI().endsWith( "/" ) )
        {
            throw new BrowserRedirectException( resource.getHref() );
        }
        resource.addLockManager( lockManager );
        return resource;
    }

    private DavResource processRepositoryGroup( final DavServletRequest request,
                                                ArchivaDavResourceLocator archivaLocator, List<String> repositories,
                                                String activePrincipal, List<String> resourcesInAbsolutePath,
                                                String repositoryGroupId )
        throws DavException
    {
        DavResource resource = null;
        List<DavException> storedExceptions = new ArrayList<DavException>();

        String pathInfo = StringUtils.removeEnd( request.getPathInfo(), "/" );

        String rootPath = StringUtils.substringBeforeLast( pathInfo, "/" );

        if ( StringUtils.endsWith( rootPath, "/.indexer" ) )
        {
            // we are in the case of index file request
            String requestedFileName = StringUtils.substringAfterLast( pathInfo, "/" );
            File temporaryIndexDirectory =
                buildMergedIndexDirectory( repositories, activePrincipal, request, repositoryGroupId );

            File resourceFile = new File( temporaryIndexDirectory, requestedFileName );
            resource = new ArchivaDavResource( resourceFile.getAbsolutePath(), requestedFileName, null,
                                               request.getRemoteAddr(), activePrincipal, request.getDavSession(),
                                               archivaLocator, this, mimeTypes, auditListeners, scheduler );

        }
        else
        {
            for ( String repositoryId : repositories )
            {
                ManagedRepositoryContent managedRepository;
                try
                {
                    managedRepository = repositoryFactory.getManagedRepositoryContent( repositoryId );
                }
                catch ( RepositoryNotFoundException e )
                {
                    throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e );
                }
                catch ( RepositoryException e )
                {
                    throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e );
                }

                try
                {
                    DavResource updatedResource =
                        processRepository( request, archivaLocator, activePrincipal, managedRepository );
                    if ( resource == null )
                    {
                        resource = updatedResource;
                    }

                    String logicalResource = RepositoryPathUtil.getLogicalResource( archivaLocator.getResourcePath() );
                    if ( logicalResource.endsWith( "/" ) )
                    {
                        logicalResource = logicalResource.substring( 1 );
                    }
                    resourcesInAbsolutePath.add(
                        new File( managedRepository.getRepoRoot(), logicalResource ).getAbsolutePath() );
                }
                catch ( DavException e )
                {
                    storedExceptions.add( e );
                }
            }
        }
        if ( resource == null )
        {
            if ( !storedExceptions.isEmpty() )
            {
                // MRM-1232
                for ( DavException e : storedExceptions )
                {
                    if ( 401 == e.getErrorCode() )
                    {
                        throw e;
                    }
                }

                throw new DavException( HttpServletResponse.SC_NOT_FOUND );
            }
            else
            {
                throw new DavException( HttpServletResponse.SC_NOT_FOUND );
            }
        }
        return resource;
    }

    private DavResource processRepository( final DavServletRequest request, ArchivaDavResourceLocator archivaLocator,
                                           String activePrincipal, ManagedRepositoryContent managedRepository )
        throws DavException
    {
        DavResource resource = null;
        if ( isAuthorized( request, managedRepository.getId() ) )
        {
            String path = RepositoryPathUtil.getLogicalResource( archivaLocator.getResourcePath() );
            if ( path.startsWith( "/" ) )
            {
                path = path.substring( 1 );
            }
            LogicalResource logicalResource = new LogicalResource( path );
            File resourceFile = new File( managedRepository.getRepoRoot(), path );
            resource = new ArchivaDavResource( resourceFile.getAbsolutePath(), path, managedRepository.getRepository(),
                                               request.getRemoteAddr(), activePrincipal, request.getDavSession(),
                                               archivaLocator, this, mimeTypes, auditListeners, scheduler );

            if ( WebdavMethodUtil.isReadMethod( request.getMethod() ) )
            {
                if ( archivaLocator.getHref( false ).endsWith( "/" ) && !resourceFile.isDirectory() )
                {
                    // force a resource not found
                    throw new DavException( HttpServletResponse.SC_NOT_FOUND, "Resource does not exist" );
                }
                else
                {
                    if ( !resource.isCollection() )
                    {
                        boolean previouslyExisted = resourceFile.exists();

                        // Attempt to fetch the resource from any defined proxy.
                        boolean fromProxy = fetchContentFromProxies( managedRepository, request, logicalResource );

                        // At this point the incoming request can either be in default or
                        // legacy layout format.
                        try
                        {
                            // Perform an adjustment of the resource to the managed
                            // repository expected path.
                            String localResourcePath =
                                repositoryRequest.toNativePath( logicalResource.getPath(), managedRepository );
                            resourceFile = new File( managedRepository.getRepoRoot(), localResourcePath );
                            resource =
                                new ArchivaDavResource( resourceFile.getAbsolutePath(), logicalResource.getPath(),
                                                        managedRepository.getRepository(), request.getRemoteAddr(),
                                                        activePrincipal, request.getDavSession(), archivaLocator, this,
                                                        mimeTypes, auditListeners, scheduler );
                        }
                        catch ( LayoutException e )
                        {
                            if ( !resourceFile.exists() )
                            {
                                throw new DavException( HttpServletResponse.SC_NOT_FOUND, e );
                            }
                        }

                        if ( fromProxy )
                        {
                            String event = ( previouslyExisted ? AuditEvent.MODIFY_FILE : AuditEvent.CREATE_FILE )
                                + PROXIED_SUFFIX;

                            log.debug( "Proxied artifact '{}' in repository '{}' (current user '{}')",
                                       resourceFile.getName(), managedRepository.getId(), activePrincipal );

                            triggerAuditEvent( request.getRemoteAddr(), archivaLocator.getRepositoryId(),
                                               logicalResource.getPath(), event, activePrincipal );
                        }

                        if ( !resourceFile.exists() )
                        {
                            throw new DavException( HttpServletResponse.SC_NOT_FOUND, "Resource does not exist" );
                        }
                    }
                }
            }

            if ( request.getMethod().equals( HTTP_PUT_METHOD ) )
            {
                String resourcePath = logicalResource.getPath();

                // check if target repo is enabled for releases
                // we suppose that release-artifacts can be deployed only to repos enabled for releases
                if ( managedRepository.getRepository().isReleases() && !repositoryRequest.isMetadata( resourcePath )
                    && !repositoryRequest.isSupportFile( resourcePath ) )
                {
                    ArtifactReference artifact = null;
                    try
                    {
                        artifact = managedRepository.toArtifactReference( resourcePath );

                        if ( !VersionUtil.isSnapshot( artifact.getVersion() ) )
                        {
                            // check if artifact already exists and if artifact re-deployment to the repository is allowed
                            if ( managedRepository.hasContent( artifact )
                                && managedRepository.getRepository().isBlockRedeployments() )
                            {
                                log.warn( "Overwriting released artifacts in repository '" + managedRepository.getId()
                                              + "' is not allowed." );
                                throw new DavException( HttpServletResponse.SC_CONFLICT,
                                                        "Overwriting released artifacts is not allowed." );
                            }
                        }
                    }
                    catch ( LayoutException e )
                    {
                        log.warn( "Artifact path '" + resourcePath + "' is invalid." );
                    }
                }

                /*
                 * Create parent directories that don't exist when writing a file This actually makes this
                 * implementation not compliant to the WebDAV RFC - but we have enough knowledge about how the
                 * collection is being used to do this reasonably and some versions of Maven's WebDAV don't correctly
                 * create the collections themselves.
                 */

                File rootDirectory = new File( managedRepository.getRepoRoot() );
                File destDir = new File( rootDirectory, logicalResource.getPath() ).getParentFile();

                if ( !destDir.exists() )
                {
                    destDir.mkdirs();
                    String relPath = PathUtil.getRelative( rootDirectory.getAbsolutePath(), destDir );

                    log.debug( "Creating destination directory '{}' (current user '{}')", destDir.getName(),
                               activePrincipal );

                    triggerAuditEvent( request.getRemoteAddr(), managedRepository.getId(), relPath,
                                       AuditEvent.CREATE_DIR, activePrincipal );
                }
            }
        }
        return resource;
    }

    public DavResource createResource( final DavResourceLocator locator, final DavSession davSession )
        throws DavException
    {
        ArchivaDavResourceLocator archivaLocator = checkLocatorIsInstanceOfRepositoryLocator( locator );

        ManagedRepositoryContent managedRepository;
        try
        {
            managedRepository = repositoryFactory.getManagedRepositoryContent( archivaLocator.getRepositoryId() );
        }
        catch ( RepositoryNotFoundException e )
        {
            throw new DavException( HttpServletResponse.SC_NOT_FOUND,
                                    "Invalid repository: " + archivaLocator.getRepositoryId() );
        }
        catch ( RepositoryException e )
        {
            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e );
        }

        String logicalResource = RepositoryPathUtil.getLogicalResource( locator.getResourcePath() );
        if ( logicalResource.startsWith( "/" ) )
        {
            logicalResource = logicalResource.substring( 1 );
        }
        File resourceFile = new File( managedRepository.getRepoRoot(), logicalResource );
        DavResource resource =
            new ArchivaDavResource( resourceFile.getAbsolutePath(), logicalResource, managedRepository.getRepository(),
                                    davSession, archivaLocator, this, mimeTypes, auditListeners, scheduler );

        resource.addLockManager( lockManager );
        return resource;
    }

    private boolean fetchContentFromProxies( ManagedRepositoryContent managedRepository, DavServletRequest request,
                                             LogicalResource resource )
        throws DavException
    {
        String path = resource.getPath();
        if ( repositoryRequest.isSupportFile( path ) )
        {
            File proxiedFile = connectors.fetchFromProxies( managedRepository, path );

            return ( proxiedFile != null );
        }

        // Is it a Metadata resource?
        if ( repositoryRequest.isDefault( path ) && repositoryRequest.isMetadata( path ) )
        {
            return connectors.fetchMetatadaFromProxies( managedRepository, path ) != null;
        }

        // Is it an Archetype Catalog?
        if ( repositoryRequest.isArchetypeCatalog( path ) )
        {
            // FIXME we must implement a merge of remote archetype catalog from remote servers.
            File proxiedFile = connectors.fetchFromProxies( managedRepository, path );

            return ( proxiedFile != null );
        }

        // Not any of the above? Then it's gotta be an artifact reference.
        try
        {
            // Get the artifact reference in a layout neutral way.
            ArtifactReference artifact = repositoryRequest.toArtifactReference( path );

            if ( artifact != null )
            {
                applyServerSideRelocation( managedRepository, artifact );

                File proxiedFile = connectors.fetchFromProxies( managedRepository, artifact );

                resource.setPath( managedRepository.toPath( artifact ) );

                log.debug( "Proxied artifact '{}:{}:{}'", artifact.getGroupId(), artifact.getArtifactId(),
                           artifact.getVersion() );

                return ( proxiedFile != null );
            }
        }
        catch ( LayoutException e )
        {
            /* eat it */
        }
        catch ( ProxyDownloadException e )
        {
            log.error( e.getMessage(), e );
            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                    "Unable to fetch artifact resource." );
        }
        return false;
    }

    /**
     * A relocation capable client will request the POM prior to the artifact, and will then read meta-data and do
     * client side relocation. A simplier client (like maven 1) will only request the artifact and not use the
     * metadatas.
     * <p/>
     * For such clients, archiva does server-side relocation by reading itself the &lt;relocation&gt; element in
     * metadatas and serving the expected artifact.
     */
    protected void applyServerSideRelocation( ManagedRepositoryContent managedRepository, ArtifactReference artifact )
        throws ProxyDownloadException
    {
        if ( "pom".equals( artifact.getType() ) )
        {
            return;
        }

        // Build the artifact POM reference
        ArtifactReference pomReference = new ArtifactReference();
        pomReference.setGroupId( artifact.getGroupId() );
        pomReference.setArtifactId( artifact.getArtifactId() );
        pomReference.setVersion( artifact.getVersion() );
        pomReference.setType( "pom" );

        // Get the artifact POM from proxied repositories if needed
        connectors.fetchFromProxies( managedRepository, pomReference );

        // Open and read the POM from the managed repo
        File pom = managedRepository.toFile( pomReference );

        if ( !pom.exists() )
        {
            return;
        }

        try
        {
            // MavenXpp3Reader leaves the file open, so we need to close it ourselves.
            FileReader reader = new FileReader( pom );
            Model model = null;
            try
            {
                model = MAVEN_XPP_3_READER.read( reader );
            }
            finally
            {
                if ( reader != null )
                {
                    reader.close();
                }
            }

            DistributionManagement dist = model.getDistributionManagement();
            if ( dist != null )
            {
                Relocation relocation = dist.getRelocation();
                if ( relocation != null )
                {
                    // artifact is relocated : update the repositoryPath
                    if ( relocation.getGroupId() != null )
                    {
                        artifact.setGroupId( relocation.getGroupId() );
                    }
                    if ( relocation.getArtifactId() != null )
                    {
                        artifact.setArtifactId( relocation.getArtifactId() );
                    }
                    if ( relocation.getVersion() != null )
                    {
                        artifact.setVersion( relocation.getVersion() );
                    }
                }
            }
        }
        catch ( FileNotFoundException e )
        {
            // Artifact has no POM in repo : ignore
        }
        catch ( IOException e )
        {
            // Unable to read POM : ignore.
        }
        catch ( XmlPullParserException e )
        {
            // Invalid POM : ignore
        }
    }

    // TODO: remove?

    private void triggerAuditEvent( String remoteIP, String repositoryId, String resource, String action,
                                    String principal )
    {
        AuditEvent event = new AuditEvent( repositoryId, principal, resource, action );
        event.setRemoteIP( remoteIP );

        for ( AuditListener listener : auditListeners )
        {
            listener.auditEvent( event );
        }
    }

    public void addAuditListener( AuditListener listener )
    {
        this.auditListeners.add( listener );
    }

    public void clearAuditListeners()
    {
        this.auditListeners.clear();
    }

    public void removeAuditListener( AuditListener listener )
    {
        this.auditListeners.remove( listener );
    }

    private void setHeaders( DavServletResponse response, DavResourceLocator locator, DavResource resource )
    {
        // [MRM-503] - Metadata file need Pragma:no-cache response
        // header.
        if ( locator.getResourcePath().endsWith( "/maven-metadata.xml" )
            || ( (ArchivaDavResource) resource ).getLocalResource().isDirectory() )
        {
            response.setHeader( "Pragma", "no-cache" );
            response.setHeader( "Cache-Control", "no-cache" );
            response.setDateHeader( "Last-Modified", new Date().getTime() );
        }
        // if the resource is a directory don't cache it as new groupId deployed will be available
        // without need of refreshing browser
        else
        {
            // We need to specify this so connecting wagons can work correctly
            response.setDateHeader( "Last-Modified", resource.getModificationTime() );
        }
        // TODO: [MRM-524] determine http caching options for other types of files (artifacts, sha1, md5, snapshots)
    }

    private ArchivaDavResourceLocator checkLocatorIsInstanceOfRepositoryLocator( DavResourceLocator locator )
        throws DavException
    {
        if ( !( locator instanceof ArchivaDavResourceLocator ) )
        {
            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                    "Locator does not implement RepositoryLocator" );
        }

        // Hidden paths
        if ( locator.getResourcePath().startsWith( ArchivaDavResource.HIDDEN_PATH_PREFIX ) )
        {
            throw new DavException( HttpServletResponse.SC_NOT_FOUND );
        }

        ArchivaDavResourceLocator archivaLocator = (ArchivaDavResourceLocator) locator;

        // MRM-419 - Windows Webdav support. Should not 404 if there is no content.
        if ( StringUtils.isEmpty( archivaLocator.getRepositoryId() ) )
        {
            throw new DavException( HttpServletResponse.SC_NO_CONTENT );
        }
        return archivaLocator;
    }

    private static class LogicalResource
    {
        private String path;

        public LogicalResource( String path )
        {
            this.path = path;
        }

        public String getPath()
        {
            return path;
        }

        public void setPath( String path )
        {
            this.path = path;
        }
    }

    protected boolean isAuthorized( DavServletRequest request, String repositoryId )
        throws DavException
    {
        try
        {
            AuthenticationResult result = httpAuth.getAuthenticationResult( request, null );
            SecuritySession securitySession = httpAuth.getSecuritySession( request.getSession( true ) );

            return servletAuth.isAuthenticated( request, result ) && servletAuth.isAuthorized( request, securitySession,
                                                                                               repositoryId,
                                                                                               WebdavMethodUtil.getMethodPermission(
                                                                                                   request.getMethod() ) );
        }
        catch ( AuthenticationException e )
        {
            // safety check for MRM-911
            String guest = UserManager.GUEST_USERNAME;
            try
            {
                if ( servletAuth.isAuthorized( guest,
                                               ( (ArchivaDavResourceLocator) request.getRequestLocator() ).getRepositoryId(),
                                               WebdavMethodUtil.getMethodPermission( request.getMethod() ) ) )
                {
                    return true;
                }
            }
            catch ( UnauthorizedException ae )
            {
                throw new UnauthorizedDavException( repositoryId,
                                                    "You are not authenticated and authorized to access any repository." );
            }

            throw new UnauthorizedDavException( repositoryId, "You are not authenticated" );
        }
        catch ( MustChangePasswordException e )
        {
            throw new UnauthorizedDavException( repositoryId, "You must change your password." );
        }
        catch ( AccountLockedException e )
        {
            throw new UnauthorizedDavException( repositoryId, "User account is locked." );
        }
        catch ( AuthorizationException e )
        {
            throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                    "Fatal Authorization Subsystem Error." );
        }
        catch ( UnauthorizedException e )
        {
            throw new UnauthorizedDavException( repositoryId, e.getMessage() );
        }
    }

    private DavResource getResource( DavServletRequest request, List<String> repositories,
                                     ArchivaDavResourceLocator locator, String groupId )
        throws DavException
    {
        List<File> mergedRepositoryContents = new ArrayList<File>();
        String path = RepositoryPathUtil.getLogicalResource( locator.getResourcePath() );
        if ( path.startsWith( "/" ) )
        {
            path = path.substring( 1 );
        }
        LogicalResource logicalResource = new LogicalResource( path );

        // flow:
        // if the current user logged in has permission to any of the repositories, allow user to
        // browse the repo group but displaying only the repositories which the user has permission to access.
        // otherwise, prompt for authentication.

        String activePrincipal = getActivePrincipal( request );

        boolean allow = isAllowedToContinue( request, repositories, activePrincipal );

        if ( allow )
        {

            // remove last /
            String pathInfo = StringUtils.removeEnd( request.getPathInfo(), "/" );
            if ( StringUtils.endsWith( pathInfo, "/.indexer" ) )
            {
                File mergedRepoDir = buildMergedIndexDirectory( repositories, activePrincipal, request, groupId );
                mergedRepositoryContents.add( mergedRepoDir );
            }
            else
            {
                for ( String repository : repositories )
                {
                    ManagedRepositoryContent managedRepository = null;

                    try
                    {
                        managedRepository = repositoryFactory.getManagedRepositoryContent( repository );
                    }
                    catch ( RepositoryNotFoundException e )
                    {
                        throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                "Invalid managed repository <" + repository + ">: " + e.getMessage() );
                    }
                    catch ( RepositoryException e )
                    {
                        throw new DavException( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                "Invalid managed repository <" + repository + ">: " + e.getMessage() );
                    }

                    File resourceFile = new File( managedRepository.getRepoRoot(), logicalResource.getPath() );
                    if ( resourceFile.exists() )
                    {
                        // in case of group displaying index directory doesn't have sense !!
                        String repoIndexDirectory = managedRepository.getRepository().getIndexDirectory();
                        if ( StringUtils.isNotEmpty( repoIndexDirectory ) )
                        {
                            if ( !new File( repoIndexDirectory ).isAbsolute() )
                            {
                                repoIndexDirectory = new File( managedRepository.getRepository().getLocation(),
                                                               StringUtils.isEmpty( repoIndexDirectory )
                                                                   ? ".indexer"
                                                                   : repoIndexDirectory ).getAbsolutePath();
                            }
                        }
                        if ( StringUtils.isEmpty( repoIndexDirectory ) )
                        {
                            repoIndexDirectory = new File( managedRepository.getRepository().getLocation(),
                                                           ".indexer" ).getAbsolutePath();
                        }

                        if ( !StringUtils.equals( FilenameUtils.normalize( repoIndexDirectory ),
                                                  FilenameUtils.normalize( resourceFile.getAbsolutePath() ) ) )
                        {
                            // for prompted authentication
                            if ( httpAuth.getSecuritySession( request.getSession( true ) ) != null )
                            {
                                try
                                {
                                    if ( isAuthorized( request, repository ) )
                                    {
                                        mergedRepositoryContents.add( resourceFile );
                                        log.debug( "Repository '{}' accessed by '{}'", repository, activePrincipal );
                                    }
                                }
                                catch ( DavException e )
                                {
                                    // TODO: review exception handling

                                    log.debug( "Skipping repository '{}' for user '{}': {}", managedRepository,
                                               activePrincipal, e.getMessage() );

                                }

                            }
                            else
                            {
                                // for the current user logged in
                                try
                                {
                                    if ( servletAuth.isAuthorized( activePrincipal, repository,
                                                                   WebdavMethodUtil.getMethodPermission(
                                                                       request.getMethod() ) ) )
                                    {
                                        mergedRepositoryContents.add( resourceFile );
                                        log.debug( "Repository '{}' accessed by '{}'", repository, activePrincipal );
                                    }
                                }
                                catch ( UnauthorizedException e )
                                {
                                    // TODO: review exception handling

                                    log.debug( "Skipping repository '{}' for user '{}': {}", managedRepository,
                                               activePrincipal, e.getMessage() );

                                }
                            }
                        }
                    }
                }
            }
        }
        else
        {
            throw new UnauthorizedDavException( locator.getRepositoryId(), "User not authorized." );
        }

        ArchivaVirtualDavResource resource =
            new ArchivaVirtualDavResource( mergedRepositoryContents, logicalResource.getPath(), mimeTypes, locator,
                                           this );

        // compatibility with MRM-440 to ensure browsing the repository group works ok
        if ( resource.isCollection() && !request.getRequestURI().endsWith( "/" ) )
        {
            throw new BrowserRedirectException( resource.getHref() );
        }

        return resource;
    }

    protected String getActivePrincipal( DavServletRequest request )
    {
        User sessionUser = httpAuth.getSessionUser( request.getSession() );
        return sessionUser != null ? sessionUser.getUsername() : UserManager.GUEST_USERNAME;
    }

    /**
     * Check if the current user is authorized to access any of the repos
     *
     * @param request
     * @param repositories
     * @param activePrincipal
     * @return
     */
    private boolean isAllowedToContinue( DavServletRequest request, List<String> repositories, String activePrincipal )
    {
        // when no repositories configured it's impossible to browse nothing !
        // at least make possible to see nothing :-)
        if ( repositories == null || repositories.isEmpty() )
        {
            return true;
        }

        boolean allow = false;

        // if securitySession != null, it means that the user was prompted for authentication
        if ( httpAuth.getSecuritySession( request.getSession() ) != null )
        {
            for ( String repository : repositories )
            {
                try
                {
                    if ( isAuthorized( request, repository ) )
                    {
                        allow = true;
                        break;
                    }
                }
                catch ( DavException e )
                {
                    continue;
                }
            }
        }
        else
        {
            for ( String repository : repositories )
            {
                try
                {
                    if ( servletAuth.isAuthorized( activePrincipal, repository,
                                                   WebdavMethodUtil.getMethodPermission( request.getMethod() ) ) )
                    {
                        allow = true;
                        break;
                    }
                }
                catch ( UnauthorizedException e )
                {
                    continue;
                }
            }
        }

        return allow;
    }

    private File writeMergedMetadataToFile( ArchivaRepositoryMetadata mergedMetadata, String outputFilename )
        throws RepositoryMetadataException, DigesterException, IOException
    {
        File outputFile = new File( outputFilename );
        if ( outputFile.exists() )
        {
            FileUtils.deleteQuietly( outputFile );
        }

        outputFile.getParentFile().mkdirs();
        RepositoryMetadataWriter.write( mergedMetadata, outputFile );

        createChecksumFile( outputFilename, digestSha1 );
        createChecksumFile( outputFilename, digestMd5 );

        return outputFile;
    }

    private void createChecksumFile( String path, Digester digester )
        throws DigesterException, IOException
    {
        File checksumFile = new File( path + digester.getFilenameExtension() );
        if ( !checksumFile.exists() )
        {
            FileUtils.deleteQuietly( checksumFile );
            checksum.createChecksum( new File( path ), digester );
        }
        else if ( !checksumFile.isFile() )
        {
            log.error( "Checksum file is not a file." );
        }
    }

    private boolean isProjectReference( String requestedResource )
    {
        try
        {
            metadataTools.toVersionedReference( requestedResource );
            return false;
        }
        catch ( RepositoryMetadataException re )
        {
            return true;
        }
    }

    protected File buildMergedIndexDirectory( List<String> repositories, String activePrincipal,
                                              DavServletRequest request, String groupId )
        throws DavException
    {

        try
        {
            HttpSession session = request.getSession();

            Map<String, TemporaryGroupIndex> temporaryGroupIndexMap =
                (Map<String, TemporaryGroupIndex>) session.getAttribute(
                    TemporaryGroupIndexSessionCleaner.TEMPORARY_INDEX_SESSION_KEY );
            if ( temporaryGroupIndexMap == null )
            {
                temporaryGroupIndexMap = new HashMap<String, TemporaryGroupIndex>();
            }

            TemporaryGroupIndex tmp = temporaryGroupIndexMap.get( groupId );

            if ( tmp != null && tmp.getDirectory() != null && tmp.getDirectory().exists() )
            {
                if ( System.currentTimeMillis() - tmp.getCreationTime() > ( indexMerger.getDefaultGroupIndexTtl() * 60
                    * 1000 ) )
                {
                    log.debug( "tmp group index is too old so delete it" );
                    indexMerger.cleanTemporaryGroupIndex( tmp );
                }
                else
                {
                    return tmp.getDirectory();
                }
            }

            Set<String> authzRepos = new HashSet<String>();
            for ( String repository : repositories )
            {
                try
                {
                    if ( servletAuth.isAuthorized( activePrincipal, repository,
                                                   WebdavMethodUtil.getMethodPermission( request.getMethod() ) ) )
                    {
                        authzRepos.add( repository );
                        authzRepos.addAll( this.repositorySearch.getRemoteIndexingContextIds( repository ) );
                    }
                }
                catch ( UnauthorizedException e )
                {
                    // TODO: review exception handling

                    log.debug( "Skipping repository '{}' for user '{}': {}", repository, activePrincipal,
                               e.getMessage() );
                }
            }
            IndexingContext indexingContext = indexMerger.buildMergedIndex( authzRepos, true );
            File mergedRepoDir = indexingContext.getIndexDirectoryFile();
            TemporaryGroupIndex temporaryGroupIndex =
                new TemporaryGroupIndex( mergedRepoDir, indexingContext.getId() ).setCreationTime(
                    new Date().getTime() );
            temporaryGroupIndexMap.put( groupId, temporaryGroupIndex );
            session.setAttribute( TemporaryGroupIndexSessionCleaner.TEMPORARY_INDEX_SESSION_KEY,
                                  temporaryGroupIndexMap );
            return mergedRepoDir;
        }
        catch ( RepositoryAdminException e )
        {
            throw new DavException( 500, e );
        }
        catch ( IndexMergerException e )
        {
            throw new DavException( 500, e );
        }
    }


    public void setServletAuth( ServletAuthenticator servletAuth )
    {
        this.servletAuth = servletAuth;
    }

    public void setHttpAuth( HttpAuthenticator httpAuth )
    {
        this.httpAuth = httpAuth;
    }

    public void setScheduler( RepositoryArchivaTaskScheduler scheduler )
    {
        this.scheduler = scheduler;
    }

    public void setArchivaConfiguration( ArchivaConfiguration archivaConfiguration )
    {
        this.archivaConfiguration = archivaConfiguration;
    }

    public void setRepositoryFactory( RepositoryContentFactory repositoryFactory )
    {
        this.repositoryFactory = repositoryFactory;
    }

    public void setRepositoryRequest( RepositoryRequest repositoryRequest )
    {
        this.repositoryRequest = repositoryRequest;
    }

    public void setConnectors( RepositoryProxyConnectors connectors )
    {
        this.connectors = connectors;
    }
}
