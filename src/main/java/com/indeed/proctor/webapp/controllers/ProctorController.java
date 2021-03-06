package com.indeed.proctor.webapp.controllers;

import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.indeed.proctor.common.ProctorLoadResult;
import com.indeed.proctor.common.ProctorSpecification;
import com.indeed.proctor.common.ProctorUtils;
import com.indeed.proctor.common.Serializers;
import com.indeed.proctor.common.TestSpecification;
import com.indeed.proctor.common.model.TestBucket;
import com.indeed.proctor.common.model.TestDefinition;
import com.indeed.proctor.common.model.TestMatrixArtifact;
import com.indeed.proctor.common.model.TestMatrixDefinition;
import com.indeed.proctor.common.model.TestMatrixVersion;
import com.indeed.proctor.webapp.ProctorSpecificationSource;
import com.indeed.proctor.webapp.db.Environment;
import com.indeed.proctor.store.ProctorStore;
import com.indeed.proctor.webapp.model.AppVersion;
import com.indeed.proctor.webapp.model.ProctorClientApplication;
import com.indeed.proctor.webapp.model.RemoteSpecificationResult;
import com.indeed.proctor.webapp.model.SessionViewModel;
import com.indeed.proctor.webapp.model.WebappConfiguration;
import com.indeed.proctor.webapp.util.threads.LogOnUncaughtExceptionHandler;
import com.indeed.proctor.webapp.views.JsonView;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Controller
@RequestMapping({"/", "/proctor"})
public class ProctorController extends AbstractController {
    private static final Logger LOGGER = Logger.getLogger(ProctorController.class);

    private final ObjectMapper objectMapper = Serializers.strict();
    private final int verificationTimeout;
    private final ExecutorService executor;
    private final ProctorSpecificationSource specificationSource;

    private static enum View {
        MATRIX_LIST("matrix/list"),
        MATRIX_USAGE("matrix/usage"),
        MATRIX_COMPATIBILITY("matrix/compatibility"),
        ERROR("error"),;

        private final String name;
        private View(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Autowired
    public ProctorController(final WebappConfiguration configuration,
                             @Qualifier("trunk") final ProctorStore trunkStore,
                             @Qualifier("qa") final ProctorStore qaStore,
                             @Qualifier("production") final ProctorStore productionStore,
            @Value("${verify.http.timeout:1000}") final int verificationTimeout,
            @Value("${verify.executor.threads:10}") final int executorThreads,
            final ProctorSpecificationSource specificationSource) {
        super(configuration, trunkStore, qaStore, productionStore);
        this.verificationTimeout = verificationTimeout;
        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("proctor-verifiers-Thread-%d")
                .setUncaughtExceptionHandler(new LogOnUncaughtExceptionHandler())
                .build();
        this.executor = Executors.newFixedThreadPool(executorThreads, threadFactory);
        this.specificationSource = specificationSource;
    }

    /**
     * TODO: this should be the default screen at /
     */
    @RequestMapping(value="/", method=RequestMethod.GET)
    public String viewTestMatrix(final String branch,
                                 final Model model) {
        final Environment which = determineEnvironmentFromParameter(branch);
        model.addAttribute("emptyClients", specificationSource.loadAllSpecifications(determineEnvironmentFromParameter(branch)).keySet().isEmpty());
        return getArtifactForView(model, which, View.MATRIX_LIST);
    }
    
    @RequestMapping(value="/matrix/raw", method=RequestMethod.GET)
    public JsonView viewRawTestMatrix(final String branch, final Model model) {
        final Environment which = determineEnvironmentFromParameter(branch);
        final TestMatrixVersion testMatrixVersion = getCurrentMatrix(which);
        final TestMatrixArtifact testMatrixArtifact = ProctorUtils.convertToConsumableArtifact(testMatrixVersion);
        return new JsonView(testMatrixArtifact);
    }

    @RequestMapping(value="/usage", method=RequestMethod.GET)
    public String viewMatrixUsage(final Model model) {
        // treemap for sorted iteration by test name
        final Map<String, CompatibilityRow> tests = Maps.newTreeMap();


        final TestMatrixVersion devMatrix = getCurrentMatrix(Environment.WORKING);
        populateTestUsageViewModel(Environment.WORKING, devMatrix, tests, Environment.WORKING);

        final TestMatrixVersion qaMatrix = getCurrentMatrix(Environment.QA);
        populateTestUsageViewModel(Environment.QA, qaMatrix, tests, Environment.QA);

        final TestMatrixVersion productionMatrix = getCurrentMatrix(Environment.PRODUCTION);
        populateTestUsageViewModel(Environment.PRODUCTION, productionMatrix, tests, Environment.PRODUCTION);

        model.addAttribute("tests", tests);
        model.addAttribute("devMatrix", devMatrix);
        model.addAttribute("qaMatrix", qaMatrix);
        model.addAttribute("productionMatrix", productionMatrix);
        model.addAttribute("session",
                           SessionViewModel.builder()
                               .setUseCompiledCSS(getConfiguration().isUseCompiledCSS())
                               .setUseCompiledJavaScript(getConfiguration().isUseCompiledJavaScript())
                               // todo get the appropriate js compile / non-compile url
                           .build());

        return View.MATRIX_USAGE.getName();
    }

    @RequestMapping(value = "/specification")
    public JsonView viewProctorSpecification(final String branch,
                                             final String app,
                                             final String version) throws IOException {
        final Environment environment = determineEnvironmentFromParameter(branch);
        final AppVersion appVersion = new AppVersion(app, version);
        final RemoteSpecificationResult spec = specificationSource.getRemoteResult(environment, appVersion);

        return new JsonView(spec);
    }


    private void populateTestUsageViewModel(final Environment matrixEnvironment,
                                            final TestMatrixVersion matrix,
                                            final Map<String, CompatibilityRow> tests,
                                            final Environment environment) {
        final TestMatrixArtifact artifact = ProctorUtils.convertToConsumableArtifact(matrix);

        final Map<AppVersion, ProctorSpecification> clients = specificationSource.loadAllSuccessfulSpecifications(environment);
        // sort the apps (probably should sort the Map.Entry, but this is good enough for now
        final SortedSet<AppVersion> versions = Sets.newTreeSet(clients.keySet());

        for (final AppVersion version : versions) {
            final ProctorSpecification specification = clients.get(version);
            for (Map.Entry<String, TestSpecification> testEntry : specification.getTests().entrySet()) {
                final String testName = testEntry.getKey();

                CompatibilityRow usageViewModel = tests.get(testName);
                if (usageViewModel == null) {
                    usageViewModel = new CompatibilityRow();
                    tests.put(testName, usageViewModel);
                }

                //
                final Map<String, TestSpecification> requiredTests = Collections.singletonMap(testEntry.getKey(), testEntry.getValue());
                final String matrixSource = matrixEnvironment.getName() + " r" + artifact.getAudit().getVersion();
                final ProctorLoadResult plr = ProctorUtils.verify(artifact, matrixSource, requiredTests);
                final boolean compatible = !plr.hasInvalidTests();
                final String error = String.format("test %s is invalid for %s", testName, matrixSource);
                usageViewModel.addVersion(environment, new CompatibleSpecificationResult(version, compatible, error));
            }
        }

        // for each of the tests in the matrix, make sure there is an entry in the usageViewModel
        for (final String testName : matrix.getTestMatrixDefinition().getTests().keySet()) {
            CompatibilityRow usageViewModel = tests.get(testName);
            if (usageViewModel == null) {
                usageViewModel = new CompatibilityRow();
                tests.put(testName, usageViewModel);
            }
        }
    }

    @RequestMapping(value="/compatibility", method=RequestMethod.GET)
    public String viewMatrixCompatibility(final Model model) {
        final Map<Environment, CompatibilityRow> compatibilityMap = Maps.newLinkedHashMap();

        populateCompabilityRow(compatibilityMap, Environment.WORKING);
        populateCompabilityRow(compatibilityMap, Environment.QA);
        populateCompabilityRow(compatibilityMap, Environment.PRODUCTION);

        model.addAttribute("compatibilityMap", compatibilityMap);
        model.addAttribute("session",
                           SessionViewModel.builder()
                               .setUseCompiledCSS(getConfiguration().isUseCompiledCSS())
                               .setUseCompiledJavaScript(getConfiguration().isUseCompiledJavaScript())
                               // todo get the appropriate js compile / non-compile url
                           .build());
        return View.MATRIX_COMPATIBILITY.getName();
    }

    private void populateCompabilityRow(final Map<Environment, CompatibilityRow> rows, final Environment rowEnv) {
        final CompatibilityRow row = new CompatibilityRow();
        rows.put(rowEnv, row);
        final TestMatrixVersion matrix = getCurrentMatrix(rowEnv);
        final TestMatrixArtifact artifact = ProctorUtils.convertToConsumableArtifact(matrix);
        populateSingleCompabilityColumn(rowEnv, artifact, row, Environment.WORKING);
        populateSingleCompabilityColumn(rowEnv, artifact, row, Environment.QA);
        populateSingleCompabilityColumn(rowEnv, artifact, row, Environment.PRODUCTION);
    }

    /**
     * We want a compatibility matrix of
     *
     * TRUNK-MATRIX:
     *      [DEV-WEBAPPS]:
     *          (web-app-1): compatible?
     *      [QA-WEBAPPS]:
     *          (web-app-1): compatible?
     *      [PRODUCTION-WEBAPPS]:
     *          (web-app-1): compatible?
     *
     * QA-MATRIX:
     *      [DEV-WEBAPPS]:
     *          (web-app-1): compatible?
     *      [QA-WEBAPPS]:
     *          (web-app-1): compatible?
     *      [PRODUCTION-WEBAPPS]:
     *          (web-app-1): compatible?
     *
     * PRODUCTION-MATRIX:
     *      [DEV-WEBAPPS]:
     *          (web-app-1): compatible?
     *      [QA-WEBAPPS]:
     *          (web-app-1): compatible?
     *      [PRODUCTION-WEBAPPS]:
     *          (web-app-1): compatible?
     *
     * @param artifact
     * @param row
     * @param webappEnvironment
     */
    private void populateSingleCompabilityColumn(
        final Environment artifactEnvironment,
        final TestMatrixArtifact artifact,
        final CompatibilityRow row,
        final Environment webappEnvironment) {
            final Map<AppVersion, RemoteSpecificationResult> clients = specificationSource.loadAllSpecifications(webappEnvironment);
            // sort the apps (probably should sort the Map.Entry, but this is good enough for now
            final SortedSet<AppVersion> versions = Sets.newTreeSet(clients.keySet());
            for(final AppVersion version : versions) {
                final RemoteSpecificationResult remoteResult = clients.get(version);
                final boolean compatible;
                final String error;
                if(remoteResult.isSuccess()) {
                    // use all the required tests from the specification
                    final String matrixSource = artifactEnvironment.getName() + " r" + artifact.getAudit().getVersion();
                    final ProctorLoadResult plr = ProctorUtils.verify(artifact, matrixSource, remoteResult.getSpecificationResult().getSpecification().getTests());
                    compatible = !plr.hasInvalidTests();
                    error = String.format("Incompatible: Tests Missing: %s Invalid Tests: %s for %s", plr.getMissingTests(), plr.getTestsWithErrors(), matrixSource);
                } else {
                    compatible = false;
                    error = "Failed to load a proctor specification from " + Joiner.on(", ").join(Iterables.transform(remoteResult.getFailures().keySet(), Functions.toStringFunction()));
                }
                row.addVersion(webappEnvironment, new CompatibleSpecificationResult(version, compatible, error));
            }
    }

    /**
     * represents a row in a compatibility matrix.
     * Contains the list of web-apps + compatibility for each environment
     *
     * For test-name by web-app break down, the compatibility should be of that web-app with a specific test + specification
     *
     * For the {matrix} by web-app break down, the compatibility should be for the web-app specification with entire matrix.
     *
     */
    public static class CompatibilityRow {
        /* all of thse should refer to dev web apps */
        final List<CompatibleSpecificationResult> dev;

        /* all of thse should refer to dev web apps */
        final List<CompatibleSpecificationResult> qa;

        /* all of thse should refer to dev web apps */
        final List<CompatibleSpecificationResult> production;


        public CompatibilityRow() {
            this.dev = Lists.newArrayList();
            this.qa = Lists.newArrayList();
            this.production = Lists.newArrayList();
        }

        public void addVersion(Environment environment, CompatibleSpecificationResult v) {
            switch (environment) {
                case WORKING:
                    dev.add(v);
                    break;
                case QA:
                    qa.add(v);
                    break;
                case PRODUCTION:
                    production.add(v);
                    break;
            }
        }

        public List<CompatibleSpecificationResult> getDev() {
            return dev;
        }

        public List<CompatibleSpecificationResult> getQa() {
            return qa;
        }

        public List<CompatibleSpecificationResult> getProduction() {
            return production;
        }
    }

    private String getArtifactForView(final Model model, final Environment branch, final View view) {
        final TestMatrixVersion testMatrix = getCurrentMatrix(branch);
        final TestMatrixDefinition testMatrixDefinition;
        if (testMatrix == null) {
            testMatrixDefinition = new TestMatrixDefinition();
        } else {
            testMatrixDefinition = testMatrix.getTestMatrixDefinition();
        }

        model.addAttribute("branch", branch);
        model.addAttribute("session",
                           SessionViewModel.builder()
                               .setUseCompiledCSS(getConfiguration().isUseCompiledCSS())
                               .setUseCompiledJavaScript(getConfiguration().isUseCompiledJavaScript())
                                   // todo get the appropriate js compile / non-compile url
                               .build());
        model.addAttribute("testMatrixVersion", testMatrix);
        final String errorMessage = "Apparently not impossible exception generating JSON";
        try {
            final String testMatrixJson = objectMapper.defaultPrettyPrintingWriter().writeValueAsString(testMatrixDefinition);
            model.addAttribute("testMatrixDefinition", testMatrixJson);

            final Map<String, Map<String, String>> colors = Maps.newHashMap();
            for (final Entry<String, TestDefinition> entry : testMatrixDefinition.getTests().entrySet()) {
                final Map<String, String> testColors = Maps.newHashMap();
                for (final TestBucket bucket : entry.getValue().getBuckets()) {
                    final long hashedBucketName = Hashing.md5().newHasher().putString(bucket.getName(), Charsets.UTF_8).hash().asLong();
                    final int color = ((int) (hashedBucketName & 0x00FFFFFFL)) | 0x00808080; //  convert a hash of the bucket to a color, but keep it light
                    testColors.put(bucket.getName(), Integer.toHexString(color));
                }
                colors.put(entry.getKey(), testColors);
            }
            model.addAttribute("colors", colors);

            return view.getName();
        } catch (final JsonGenerationException e) {
            LOGGER.error(errorMessage, e);
            model.addAttribute("exception", toString(e));
        } catch (final JsonMappingException e) {
            LOGGER.error(errorMessage, e);
            model.addAttribute("exception", toString(e));
        } catch (final IOException e) {
            LOGGER.error(errorMessage, e);
            model.addAttribute("exception", toString(e));
        }
        model.addAttribute("error", errorMessage);
        return View.ERROR.getName();
    }

    /**
     * This needs to be moved to a separate checker class implementing some interface
     */
    private URL getSpecificationUrl(final ProctorClientApplication client) {
        final String urlStr = client.getBaseApplicationUrl() + "/private/proctor";
        try {
            return new URL(urlStr);
        } catch (final MalformedURLException e) {
            throw new RuntimeException("Somehow created a malformed URL: " + urlStr, e);
        }
    }

    private static String toString(final Throwable t) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.close();
        return sw.toString();
    }

    public static class CompatibleSpecificationResult {
        private final AppVersion appVersion;
        private final boolean isCompatible;
        private final String error;

        public CompatibleSpecificationResult(AppVersion version,
                                             boolean compatible,
                                             String error) {
            this.appVersion = version;
            isCompatible = compatible;
            this.error = error;
        }

        public AppVersion getAppVersion() {
            return appVersion;
        }

        public boolean isCompatible() {
            return isCompatible;
        }

        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            return appVersion.toString();
        }
    }

}
