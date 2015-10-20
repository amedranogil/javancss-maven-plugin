package org.codehaus.mojo.javancss;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import org.apache.maven.model.ReportPlugin;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.PathTool;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;

/**
 * Generates a JavaNCSS report based on this module's source code.
 *
 * @author <a href="jeanlaurentATgmail.com">Jean-Laurent de Morlhon</a>
 * @version $Id$
 */
@Mojo( name = "report" )
public class NcssReportMojo
    extends AbstractMavenReport
{
    private static final String OUTPUT_NAME = "javancss";

    /**
     * Specifies the directory where the XML report will be generated.
     */
    @Parameter( defaultValue = "${project.build.directory}", readonly = true, required = true )
    private File xmlOutputDirectory;

    /**
     * Specifies the location of the source files to be used.
     */
    @Parameter( defaultValue = "${project.build.sourceDirectory}", readonly = true, required = true )
    private File sourceDirectory;

    /**
     * Specifies the maximum number of lines to take into account into the reports.
     */
    @Parameter( defaultValue = "30" )
    private int lineThreshold;

    /**
     * Specified the name of the temporary file generated by JavaNCSS prior report generation.
     */
    @Parameter( defaultValue = "javancss-raw-report.xml" )
    private String tempFileName;

    /**
     * The projects in the reactor for aggregation report.
     */
    @Parameter( defaultValue = "${reactorProjects}", readonly = true, required = true )
    private List<MavenProject> reactorProjects;

    /**
     * Link the violation line numbers to the source xref. Defaults to true and will link automatically if jxr plugin is
     * being used.
     */
    @Parameter( property = "linkXRef", defaultValue = "true" )
    private boolean linkXRef;

    /**
     * Location of the Xrefs to link to.
     */
    @Parameter( defaultValue = "${project.build.directory}/site/xref" )
    private File xrefLocation;

    /**
     * List of ant-style patterns used to specify the java sources that should be included when running JavaNCSS. If
     * this is not specified, all .java files in the project source directories are included.
     */
    @Parameter
    private String[] includes;

    /**
     * List of ant-style patterns used to specify the java sources that should be excluded when running JavaNCSS. If
     * this is not specified, no files in the project source directories are excluded.
     */
    @Parameter
    private String[] excludes;

    /**
     * Skip the report.
     *
     * @since 2.1
     */
    @Parameter( property = "ncss.skip", defaultValue = "false" )
    private boolean skip;

    
    /**
     * The relative path to generate / find XMl reports.
     */
    private String relativeXMLOutputDirectory;

	/**
     * @see org.apache.maven.reporting.MavenReport#executeReport(java.util.Locale)
     */
    public void executeReport( Locale locale )
        throws MavenReportException
    {
    	
    	// All this work just to get "target" so that we can scan the filesystem for
        // child javancss xml files...
        String basedir = project.getBasedir().toString();
        String output = xmlOutputDirectory.toString();
        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "basedir: " + basedir );
            getLog().debug( "output: " + output );
        }
        if ( output.startsWith( basedir ) )
        {
            relativeXMLOutputDirectory = output.substring( basedir.length() + 1 );
        }
        else
        {
            getLog().error(
                            "Unable to aggregate report because I can't "
                                + "determine the relative location of the XML report" );
            return;
        }
        getLog().debug( "relative: " + relativeXMLOutputDirectory );
    	
    	LinkedList forLater = new LinkedList(); // list to reorder execution of aggregated projects
    	for (Iterator i = reactorProjects.iterator(); i.hasNext();) 
    	{
    		MavenProject mp = (MavenProject) i.next();
    		ProjectReporter pr = new ProjectReporter(mp);

    		if (pr.canGenerateSingleReport()) 
    		{
    			getLog().debug("running Single Reporting for: " + pr.project.getGroupId() + ":" + pr.project.getArtifactId() +":" + pr.project.getVersion());
    			pr.generateSingleReport(locale);
    		}
    		if (project.getModules().size() > 0 && !pr.canGenerateAggregateReport())
    		{	
    			//an aggregated project that can not be generated at this moment -> try later
    			forLater.addFirst(pr);
    		}
    	}
    	
    	while (!forLater.isEmpty())
    	{
    		ProjectReporter pr = (ProjectReporter) forLater.getFirst();
    		forLater.removeFirst();
    		if (pr.canGenerateAggregateReport())
    		{
    			getLog().debug("running Aggregate Reporting for: " + pr.project.getGroupId() + ":" + pr.project.getArtifactId() +":" + pr.project.getVersion());
    			pr.generateAggregateReport(locale);
    		}
    		else 
    		{
    			// Aggregate report could not be generated, maybe because it depends on other aggregate reports try again later.
    			forLater.addLast(pr);
    		}
    	}
    }
    private class ProjectReporter {
    	

        private MavenProject project;
        
        private File sourceDirectory;
       
        ProjectReporter(MavenProject mp){
        	project = mp;
        	sourceDirectory = new File (project.getBuild().getSourceDirectory());
        }

        private void generateAggregateReport( Locale locale )
        		throws MavenReportException
        		{
        	// All this work just to get "target" so that we can scan the filesystem for
        	// child javancss xml files...
        	
        	getLog().debug( "relative: " + relativeXMLOutputDirectory );
        	List<ModuleReport> reports = new ArrayList<ModuleReport>();
        	for ( MavenProject child : reactorProjects )
        	{
        		File xmlReport = new File( child.getBasedir() + File.separator + relativeXMLOutputDirectory, tempFileName );
        		if ( xmlReport.exists() )
        		{
        			reports.add( new ModuleReport( child, loadDocument( xmlReport ) ) );
        		}
        		else
        		{
        			getLog().debug( "xml file not found: " + xmlReport );
        		}
        	}
        	getLog().debug( "Aggregating " + reports.size() + " JavaNCSS reports" );

        	// parse the freshly generated file and write the report
        	NcssAggregateReportGenerator reportGenerator =
        			new NcssAggregateReportGenerator( getSink(), getBundle( locale ), getLog() );
        	reportGenerator.doReport( locale, reports, lineThreshold );
        		}

        private boolean isIncludeExcludeUsed()
        {
        	return ( ( excludes != null ) || ( includes != null ) );
        }

        private void generateSingleReport( Locale locale )
        		throws MavenReportException
        		{
        	getLog().info( "Running JavaNCSS " + NcssExecuter.getJavaNCSSVersion() );
        	if ( getLog().isDebugEnabled() )
        	{
        		getLog().debug( "Calling NcssExecuter with src: " + sourceDirectory );
        		getLog().debug( "                       output: " + buildOutputFileName() );
        		getLog().debug( "                     includes: " + includes );
        		getLog().debug( "                     excludes: " + excludes );
        		getLog().debug( "                     encoding: " + getInputEncoding() );
        	}

        	// run javaNCss and produce an temp xml file
        	NcssExecuter ncssExecuter;
        	if ( isIncludeExcludeUsed() )
        	{
        		ncssExecuter = new NcssExecuter( scanForSources(), buildOutputFileName() );
        	}
        	else
        	{
        		ncssExecuter = new NcssExecuter( sourceDirectory, buildOutputFileName() );
        	}
        	ncssExecuter.setEncoding( getInputEncoding() ); // in case of null value, JavaNCSS uses platform encoding, as
        	// expected

        	ncssExecuter.execute();
        	if ( !isTempReportGenerated() )
        	{
        		throw new MavenReportException( "Can't process temp ncss xml file." );
        	}
        	// parse the freshly generated file and write the report
        	NcssReportGenerator reportGenerator =
        			new NcssReportGenerator( getSink(), getBundle( locale ), getLog(), constructXRefLocation() );
        	reportGenerator.doReport( loadDocument(), lineThreshold );
        		}

        /**
         * Load the xml file generated by javancss.
         */
        private Document loadDocument( File file )
        		throws MavenReportException
        		{
        	try
        	{
        		SAXReader saxReader = new SAXReader();
        		return saxReader.read( ReaderFactory.newXmlReader( file ) );
        	}
        	catch ( DocumentException de )
        	{
        		throw new MavenReportException( de.getMessage(), de );
        	}
        	catch ( IOException ioe )
        	{
        		throw new MavenReportException( ioe.getMessage(), ioe );
        	}
        		}

        private Document loadDocument()
        		throws MavenReportException
        		{
        	return loadDocument( new File( buildOutputFileName() ) );
        		}

        /**
         * Check that the expected temporary file generated by JavaNCSS exists.
         *
         * @return <code>true</code> if the temporary report exists, <code>false</code> otherwise.
         */
        private boolean isTempReportGenerated()
        {
        	return new File( buildOutputFileName() ).exists();
        }

        /**
         * @see org.apache.maven.reporting.MavenReport#canGenerateReport()
         */
        public boolean canGenerateReport()
        {
        	return !skip && ( canGenerateSingleReport() || canGenerateAggregateReport() );
        }

        private boolean canGenerateAggregateReport()
        {
        	if ( project.getModules().size() == 0 )
        	{
        		// no child modules
        		return false;
        	}
        	if ( sourceDirectory != null && sourceDirectory.exists() )
        	{
        		// only non-source projects can aggregate
        		String[] sources = scanForSources();
        		return !( ( sources != null ) && ( sources.length > 0 ) );
        	}
        	return true;
        }

        private boolean canGenerateSingleReport()
        {
        	if ( sourceDirectory == null || !sourceDirectory.exists() )
        	{
        		return false;
        	}
        	// now that we know we have a valid existing source directory
        	// we check if any *.java files are existing.
        	String[] sources = scanForSources();
        	return ( sources != null ) && ( sources.length > 0 );
        }

        /**
         * gets a list of all files in the source directory.
         *
         * @return the list of all files in the source directory;
         */
        private String[] scanForSources()
        {
        	String[] defaultIncludes = { "**\\*.java" };
        	DirectoryScanner ds = new DirectoryScanner();
        	if ( includes == null )
        	{
        		ds.setIncludes( defaultIncludes );
        	}
        	else
        	{
        		ds.setIncludes( includes );
        	}
        	if ( excludes != null )
        	{
        		ds.setExcludes( excludes );
        	}
        	ds.setBasedir( sourceDirectory );
        	getLog().debug( "Scanning base directory " + sourceDirectory );
        	ds.scan();
        	int maxFiles = ds.getIncludedFiles().length;
        	String[] result = new String[maxFiles];
        	for ( int i = 0; i < maxFiles; i++ )
        	{
        		result[i] = sourceDirectory + File.separator + ds.getIncludedFiles()[i];
        	}
        	return result;
        }

        /**
         * Build a path for the output filename.
         *
         * @return A String representation of the output filename.
         */
        /* package */String buildOutputFileName()
        {
        	File outputDir = new File(project.getBasedir().getAbsolutePath() + File.separator + relativeXMLOutputDirectory);
        	if (!outputDir.exists())
        	{
        		outputDir.mkdirs();
        	}
        	
        	return project.getBasedir().getAbsolutePath() + File.separator + relativeXMLOutputDirectory + File.separator + tempFileName;
        }
    }
    /**
     * @see org.apache.maven.reporting.MavenReport#getName(java.util.Locale)
     */
    public String getName( Locale locale )
    {
        return getBundle( locale ).getString( "report.javancss.name" );
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#getDescription(java.util.Locale)
     */
    public String getDescription( Locale locale )
    {
        return getBundle( locale ).getString( "report.javancss.description" );
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#getOutputName()
     */
    public String getOutputName()
    {
        return OUTPUT_NAME;
    }

    /**
     * Getter for the source directory
     *
     * @return the source directory as a File object.
     */
    protected File getSourceDirectory()
    {
        return sourceDirectory;
    }

    // helper to retrieve the right bundle
    private static ResourceBundle getBundle( Locale locale )
    {
        return ResourceBundle.getBundle( "javancss-report", locale, NcssReportMojo.class.getClassLoader() );
    }

    // blatantly copied from maven pmd plugin
    protected String constructXRefLocation()
    {
        String location = null;
        if ( linkXRef )
        {
            String relativePath =
                PathTool.getRelativePath( outputDirectory.getAbsolutePath(), xrefLocation.getAbsolutePath() );
            if ( StringUtils.isEmpty( relativePath ) )
            {
                relativePath = ".";
            }
            relativePath = relativePath + "/" + xrefLocation.getName();
            if ( xrefLocation.exists() )
            {
                // XRef was already generated by manual execution of a lifecycle binding
                location = relativePath;
            }
            else
            {
                // Not yet generated - check if the report is on its way
                for ( ReportPlugin plugin : (List<ReportPlugin>) project.getReportPlugins() )
                {
                    String artifactId = plugin.getArtifactId();
                    if ( "maven-jxr-plugin".equals( artifactId ) || "jxr-maven-plugin".equals( artifactId ) )
                    {
                        location = relativePath;
                    }
                }
            }

            if ( location == null )
            {
                getLog().warn( "Unable to locate Source XRef to link to - DISABLED" );
            }
        }
        return location;
    }
}
