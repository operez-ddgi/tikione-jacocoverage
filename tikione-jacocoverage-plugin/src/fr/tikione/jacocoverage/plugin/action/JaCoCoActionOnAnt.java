package fr.tikione.jacocoverage.plugin.action;

import fr.tikione.jacocoexec.analyzer.JaCoCoReportAnalyzer;
import fr.tikione.jacocoexec.analyzer.JaCoCoXmlReportParser;
import fr.tikione.jacocoexec.analyzer.JavaClass;
import fr.tikione.jacocoverage.plugin.anno.AbstractCoverageAnnotation;
import fr.tikione.jacocoverage.plugin.config.Config;
import fr.tikione.jacocoverage.plugin.config.Globals;
import fr.tikione.jacocoverage.plugin.util.NBUtils;
import fr.tikione.jacocoverage.plugin.util.Utils;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.tools.ant.module.api.AntProjectCookie;
import org.apache.tools.ant.module.api.AntTargetExecutor;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.progress.ProgressHandleFactory;
import org.netbeans.api.project.Project;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.HtmlBrowser;
import org.openide.execution.ExecutorTask;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;
import org.openide.util.Utilities;
import org.xml.sax.SAXException;

/**
 * A toolkit that launches Ant tasks with the JaCoCo JavaAgent, colorizes Java source files and shows a coverage report.
 * <br/>See <a href="http://wiki.netbeans.org/DevFaqRequestProcessor">DevFaqRequestProcessor</a> for NetBeans threading tweaks.
 * <br/>See <a href="http://wiki.netbeans.org/DevFaqActionContextSensitive">DevFaqActionContextSensitive</a> for context action tweaks.
 * <br/>See <a href="http://wiki.netbeans.org/DevFaqAddGlobalContext">DevFaqAddGlobalContext</a> for global context and project tweaks.
 *
 * @author Jonathan Lermitage
 */
public abstract class JaCoCoActionOnAnt
        extends AbstractAction
        implements ActionListener {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(JaCoCoActionOnAnt.class.getName());

    /** The Ant task to launch. */
    private final String antTask;

    /**
     * Enable the context action on supported projects only.
     *
     * @param project the project the contextual action is called from.
     * @param antTask additional properties passed to the Ant task.
     */
    public JaCoCoActionOnAnt(String antTask) {
        this.antTask = antTask;
    }

    public @Override
    void actionPerformed(ActionEvent ae) {
        final Project project = NBUtils.getSelectedProject();
        new RequestProcessor("JaCoCoverage Action Task", 3, true).post(new Runnable() {
            @Override
            public void run() {
                try {
                    // Retrieve JaCoCoverage preferences.
                    final boolean enblHighlight = Config.isEnblHighlighting();
                    final boolean enblConsoleReport = Config.isEnblConsoleReport();
                    final boolean enblHtmlReport = Config.isEnblHtmlReport();
                    final boolean openHtmlReport = Config.isOpenHtmlReport();

                    if (enblHighlight || enblConsoleReport || enblHtmlReport) {
                        // Retrieve project properties.
                        final String prjDir = NBUtils.getProjectDir(project) + File.separator;
                        FileObject prjPropsFo = project.getProjectDirectory().getFileObject("nbproject/project.properties");
                        final Properties prjProps = new Properties();
                        prjProps.load(prjPropsFo.getInputStream());

                        final File xmlreport = Utils.getJacocoXmlReportfile(project);
                        final File binreport = Utils.getJacocoBinReportFile(project);
                        if (binreport.exists() && !binreport.delete() || xmlreport.exists() && !xmlreport.delete()) {
                            String msg = "Cannot delete the previous JaCoCo report files, please delete them manually:\n"
                                    + binreport.getAbsolutePath() + " and/or\n"
                                    + xmlreport.getAbsolutePath();
                            NotifyDescriptor nd = new NotifyDescriptor.Message(msg, NotifyDescriptor.ERROR_MESSAGE);
                            DialogDisplayer.getDefault().notify(nd);
                        } else {
                            // Apply JaCoCo JavaAgent customization.
                            String antTaskJavaagentParam = Config.getAntTaskJavaagentArg()
                                    .replace("{pathOfJacocoagentJar}", NBUtils.getJacocoAgentJar().getAbsolutePath())
                                    .replace("{appPackages}", NBUtils.getProjectJavaPackagesAsStr(project, prjProps, ":", ".*"))
                                    .replace("destfile=jacoco.exec", "destfile=\"" + prjDir + "jacoco.exec\"");

                            FileObject scriptToExecute = project.getProjectDirectory().getFileObject("build", "xml");
                            DataObject dataObj = DataObject.find(scriptToExecute);
                            AntProjectCookie antCookie = dataObj.getLookup().lookup(AntProjectCookie.class);

                            AntTargetExecutor.Env env = new AntTargetExecutor.Env();
                            AntTargetExecutor executor = AntTargetExecutor.createTargetExecutor(env);

                            // Add the customized JaCoCo JavaAgent to the JVM arguments given to the Ant task. The JaCoCo JavaAgent is
                            // appended to the existing list of JVM arguments that is given to the Ant task.
                            Properties targetProps = env.getProperties();
                            String prjJvmArgs = Utils.getProperty(prjProps, "run.jvmargs");
                            targetProps.put("run.jvmargs", prjJvmArgs + "  -javaagent:" + antTaskJavaagentParam);
                            env.setProperties(targetProps);

                            // Launch the Ant task with the JaCoCo JavaAgent.
                            final ExecutorTask execute = executor.execute(antCookie, new String[]{antTask});

                            new RequestProcessor("JaCoCoverage Collection Task", 3, true).post(new Runnable() {
                                @Override
                                public void run() {
                                    ProgressHandle progr = ProgressHandleFactory.createHandle("JaCoCoverage Collection Task");
                                    try {
                                        progr.setInitialDelay(400);
                                        progr.start();
                                        progr.switchToIndeterminate();

                                        // Wait for the end of the Ant task execution. We do it in a new thread otherwise it would
                                        // freeze the current one. This is a workaround for a known and old NetBeans bug: the ExecutorTask
                                        // object provided by the NetBeans platform is not correctly wrapped.
                                        int executeRes = execute.result();
                                        if (0 == executeRes && binreport.exists()) {
                                            long st = System.currentTimeMillis();
                                            // Load the generated JaCoCo coverage report.
                                            File classDir = new File(
                                                    prjDir + Utils.getProperty(prjProps, "build.classes.dir") + File.separator);
                                            File srcDir = new File(prjDir + Utils.getProperty(prjProps, "src.dir") + File.separator);
                                            JaCoCoReportAnalyzer.toXmlReport(binreport, xmlreport, classDir, srcDir);
                                            final Map<String, JavaClass> coverageData = JaCoCoXmlReportParser.getCoverageData(xmlreport);
                                            new File(prjDir + Globals.JACOCOVERAGE_DATA_DIR).mkdirs();

                                            // Remove existing highlighting (from a previous coverage task), show reports and apply
                                            // highlighting on each Java source file.
                                            AbstractCoverageAnnotation.removeAll(NBUtils.getProjectId(project));
                                            String prjname = NBUtils.getProjectName(project);
                                            if (enblConsoleReport) {
                                                JaCoCoReportAnalyzer.toConsoleReport(coverageData, prjname + Globals.TXTREPORT_TABNAME);
                                            }
                                            File reportdir = new File(prjDir + Globals.HTML_REPORT_DIR);
                                            if (reportdir.exists()) {
                                                org.apache.commons.io.FileUtils.deleteDirectory(reportdir);
                                            }
                                            if (enblHtmlReport) {
                                                reportdir.mkdirs();
                                                String report = JaCoCoReportAnalyzer.toHtmlReport(
                                                        binreport, reportdir, classDir, srcDir, prjname);
                                                if (openHtmlReport) {
                                                    try {
                                                        HtmlBrowser.URLDisplayer.getDefault().showURL(
                                                                Utilities.toURI(new File(report)).toURL());
                                                    } catch (MalformedURLException ex) {
                                                        Exceptions.printStackTrace(ex);
                                                    }
                                                }
                                            }
                                            if (enblHighlight) {
                                                for (final JavaClass jclass : coverageData.values()) {
                                                    NBUtils.colorDoc(project, jclass);
                                                }
                                            }
                                            keepJaCoCoWorkfiles(binreport, xmlreport, prjDir);

                                            long et = System.currentTimeMillis();
                                            LOGGER.log(Level.INFO, "Coverage Collection Task took: {0} ms", et - st);
                                        } else {
                                            AbstractCoverageAnnotation.removeAll(NBUtils.getProjectId(project));
                                            NBUtils.closeConsoleTab(Globals.TXTREPORT_TABNAME);
                                            String msg = "Ant Task or JaCoCo Agent failed, JaCoCoverage can't process data.\n"
                                                    + "(AntExitCode=" + executeRes + ", JacocoBinReportFound=" + binreport.exists() + ")";
                                            NotifyDescriptor nd = new NotifyDescriptor.Message(msg, NotifyDescriptor.WARNING_MESSAGE);
                                            DialogDisplayer.getDefault().notify(nd);
                                        }
                                    } catch (FileNotFoundException ex) {
                                        Exceptions.printStackTrace(ex);
                                    } catch (IOException ex) {
                                        Exceptions.printStackTrace(ex);
                                    } catch (ParserConfigurationException ex) {
                                        Exceptions.printStackTrace(ex);
                                    } catch (SAXException ex) {
                                        Exceptions.printStackTrace(ex);
                                    } finally {
                                        progr.finish();
                                    }
                                }
                            });
                        }
                    } else {
                        String msg = "Please enable at least one JaCoCoverage feature first (highlighting or reporting).";
                        NotifyDescriptor nd = new NotifyDescriptor.Message(msg, NotifyDescriptor.ERROR_MESSAGE);
                        DialogDisplayer.getDefault().notify(nd);
                    }
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                } catch (IllegalArgumentException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        });
    }

    private void keepJaCoCoWorkfiles(File binreport, File xmlreport, String prjDir)
            throws IOException {
        File xmlreportCpy = new File(prjDir + Globals.XML_BACKUP_REPORT);
        File xmlreportZip = new File(prjDir + Globals.XMLZIP_BACKUP_REPORT);
        File binreportCpy = new File(prjDir + Globals.BIN_BACKUP_REPORT);
        File binreportZip = new File(prjDir + Globals.BINZIP_BACKUP_REPORT);
        xmlreportCpy.delete();
        xmlreportZip.delete();
        binreportCpy.delete();
        binreportZip.delete();
        switch (Config.getJaCoCoWorkfilesRule()) {
            case 0:
                org.apache.commons.io.FileUtils.moveFile(binreport, binreportCpy);
                org.apache.commons.io.FileUtils.moveFile(xmlreport, xmlreportCpy);
                break;
            case 1:
                Utils.zip(binreport, binreportZip, Globals.BINZIP_BACKUP_REPORT_ENTRY, false);
                Utils.zip(xmlreport, xmlreportZip, Globals.XMLZIP_BACKUP_REPORT_ENTRY, false);
                break;
            case 2:
                break;
        }
        binreport.delete();
        xmlreport.delete();
    }
}