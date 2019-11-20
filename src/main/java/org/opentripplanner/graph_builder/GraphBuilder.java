package org.opentripplanner.graph_builder;

import com.google.common.collect.Lists;
import org.opentripplanner.api.common.ParameterException;
import org.opentripplanner.ext.transferanalyzer.DirectTransferAnalyzer;
import org.opentripplanner.graph_builder.model.GtfsBundle;
import org.opentripplanner.graph_builder.module.DirectTransferGenerator;
import org.opentripplanner.graph_builder.module.EmbedConfig;
import org.opentripplanner.graph_builder.module.GtfsModule;
import org.opentripplanner.graph_builder.module.PruneFloatingIslands;
import org.opentripplanner.graph_builder.module.StreetLinkerModule;
import org.opentripplanner.graph_builder.module.TransitToTaggedStopsModule;
import org.opentripplanner.graph_builder.module.map.BusRouteStreetMatcher;
import org.opentripplanner.graph_builder.module.ned.DegreeGridNEDTileSource;
import org.opentripplanner.graph_builder.module.ned.ElevationModule;
import org.opentripplanner.graph_builder.module.ned.GeotiffGridCoverageFactoryImpl;
import org.opentripplanner.graph_builder.module.ned.NEDGridCoverageFactoryImpl;
import org.opentripplanner.graph_builder.module.osm.OpenStreetMapModule;
import org.opentripplanner.graph_builder.services.DefaultStreetEdgeFactory;
import org.opentripplanner.graph_builder.services.GraphBuilderModule;
import org.opentripplanner.graph_builder.services.ned.ElevationGridCoverageFactory;
import org.opentripplanner.openstreetmap.impl.AnyFileBasedOpenStreetMapProviderImpl;
import org.opentripplanner.openstreetmap.services.OpenStreetMapProvider;
import org.opentripplanner.reflect.ReflectionLibrary;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.standalone.CommandLineParameters;
import org.opentripplanner.standalone.GraphBuilderParameters;
import org.opentripplanner.standalone.S3BucketConfig;
import org.opentripplanner.standalone.config.GraphConfig;
import org.opentripplanner.util.OTPFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.opentripplanner.netex.configure.NetexConfig.netexModule;

/**
 * This makes a Graph out of various inputs like GTFS and OSM.
 * It is modular: GraphBuilderModules are placed in a list and run in sequence.
 */
public class GraphBuilder implements Runnable {

    private static String GRAPH_FILENAME = "Graph.obj";

    private static String OSM_GRAPH_FILENAME = "osmGraph.obj";

    private static Logger LOG = LoggerFactory.getLogger(GraphBuilder.class);

    private List<GraphBuilderModule> graphBuilderModules = new ArrayList<>();

    private final File graphFile;
    
    private Graph graph = new Graph();

    /** Should the graph be serialized to disk after being created or not? */
    private boolean serializeGraph = true;

    private GraphBuilder(File path) {
        graphFile = new File(path, GRAPH_FILENAME);
    }

    private void addModule(GraphBuilderModule loader) {
        graphBuilderModules.add(loader);
    }

    public Graph getGraph() {
        return this.graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }

    public void run() {
        /* Record how long it takes to build the graph, purely for informational purposes. */
        long startTime = System.currentTimeMillis();

        if (serializeGraph) {
            if (graphFile.exists()) {
                LOG.info("Graph already exists and will be overwritten at the end of the build process.");
            }
            try {
                if (!graphFile.getParentFile().exists()) {
                    if (!graphFile.getParentFile().mkdirs()) {
                        LOG.error("Failed to create directories for graph bundle at " + graphFile);
                    }
                }
                graphFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Cannot create or overwrite graph at path " + graphFile);
            }
        }

        // Check all graph builder inputs, and fail fast to avoid waiting until the build process advances.
        for (GraphBuilderModule builder : graphBuilderModules) {
            builder.checkInputs();
        }

        DataImportIssueStore issueStore = new DataImportIssueStore(true);
        
        HashMap<Class<?>, Object> extra = new HashMap<Class<?>, Object>();
        for (GraphBuilderModule load : graphBuilderModules)
            load.buildGraph(graph, extra, issueStore);

        issueStore.summarize();

        if (serializeGraph) {
            try {
                graph.save(graphFile);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        } else {
            LOG.info("Not saving graph to disk, as requested.");
        }

        long endTime = System.currentTimeMillis();
        LOG.info(String.format("Graph building took %.1f minutes.", (endTime - startTime) / 1000 / 60.0));
    }


    /**
     * Factory method to create and configure a GraphBuilder with all the appropriate modules
     * to build a graph from the files in the given configuration.
     * Note of all command line options this is only using  params.inMemory params.preFlight
     * and params.build directory
     */
    public static GraphBuilder create(CommandLineParameters params, GraphConfig config) {
        LOG.info("Wiring up and configuring graph builder task.");
        List<File> gtfsFiles = Lists.newArrayList();
        List<File> netexFiles = Lists.newArrayList();
        List<File> osmFiles =  Lists.newArrayList();
        File demFile = null;
        File dir = params.getGraphDirectory();

        LOG.info("Searching for graph builder input files in {}", dir);

        // Find and parse config files first to reveal syntax errors early without waiting for graph build.
        GraphBuilderParameters builderParams = new GraphBuilderParameters(config.builderConfig());

        GraphBuilder graphBuilder = new GraphBuilder(dir);

        if (params.loadOSMGraph) {
            File graphFile = new File(dir, OSM_GRAPH_FILENAME);
            try {
                Graph osmGraph = Graph.load(new FileInputStream(graphFile));
                if (osmGraph.hasTransit) {
                    throw new IllegalArgumentException("OSM graph cannot contain transit data.");
                }
                graphBuilder.setGraph(osmGraph);
            } catch (FileNotFoundException ex) {
                throw new IllegalArgumentException("OSM graph not found.");
            }
        }

        // Load the router config JSON to fail fast, but we will only apply it later when a router
        // starts up
        config.routerConfig();
        LOG.info(ReflectionLibrary.dumpFields(builderParams));

        for (File file : dir.listFiles()) {
            switch (InputFileType.forFile(file, builderParams)) {
                case GTFS:
                    LOG.info("Found GTFS file {}", file);
                    gtfsFiles.add(file);
                    break;
                case OSM:
                    LOG.info("Found OSM file {}", file);
                    osmFiles.add(file);
                    break;
                case DEM:
                    if (!builderParams.fetchElevationUS && demFile == null) {
                        LOG.info("Found DEM file {}", file);
                        demFile = file;
                    } else {
                        LOG.info("Skipping DEM file {}", file);
                    }
                    break;
            case NETEX:
                    LOG.info("Found NETEX file {}", file);
                    netexFiles.add(file);
                    break;
                case OTHER:
                    LOG.warn("Skipping unrecognized file '{}'", file);
            }
        }
        boolean hasOsm  = builderParams.streets && !osmFiles.isEmpty();
        boolean hasGtfs = builderParams.transit && !gtfsFiles.isEmpty();
        boolean hasNetex = builderParams.transit && !netexFiles.isEmpty();
        boolean hasTransitData = hasGtfs || hasNetex;

        if ( ! ( hasOsm || hasGtfs || hasNetex)) {
            LOG.error("Found no input files from which to build a graph in {}", dir);
            return null;
        }
        if ( hasOsm ) {
            List<OpenStreetMapProvider> osmProviders = Lists.newArrayList();
            for (File osmFile : osmFiles) {
                OpenStreetMapProvider osmProvider = new AnyFileBasedOpenStreetMapProviderImpl(osmFile);
                osmProviders.add(osmProvider);
            }
            OpenStreetMapModule osmModule = new OpenStreetMapModule(osmProviders);
            DefaultStreetEdgeFactory streetEdgeFactory = new DefaultStreetEdgeFactory();
            streetEdgeFactory.useElevationData = builderParams.fetchElevationUS || (demFile != null);
            osmModule.edgeFactory = streetEdgeFactory;
            osmModule.customNamer = builderParams.customNamer;
            osmModule.setDefaultWayPropertySetSource(builderParams.wayPropertySet);
            osmModule.skipVisibility = !builderParams.areaVisibility;
            osmModule.platformEntriesLinking = builderParams.platformEntriesLinking;
            osmModule.staticBikeRental = builderParams.staticBikeRental;
            osmModule.staticBikeParkAndRide = builderParams.staticBikeParkAndRide;
            osmModule.staticParkAndRide = builderParams.staticParkAndRide;
            osmModule.banDiscouragedWalking = builderParams.banDiscouragedWalking;
            osmModule.banDiscouragedBiking = builderParams.banDiscouragedBiking;
            graphBuilder.addModule(osmModule);
            PruneFloatingIslands pruneFloatingIslands = new PruneFloatingIslands();
            pruneFloatingIslands.setPruningThresholdIslandWithoutStops(builderParams.pruningThresholdIslandWithoutStops);
            pruneFloatingIslands.setPruningThresholdIslandWithStops(builderParams.pruningThresholdIslandWithStops);
            graphBuilder.addModule(pruneFloatingIslands);
        }
        if ( hasGtfs ) {
            List<GtfsBundle> gtfsBundles = Lists.newArrayList();
            for (File gtfsFile : gtfsFiles) {
                GtfsBundle gtfsBundle = new GtfsBundle(gtfsFile);

                // TODO OTP2 - In OTP2 we have deleted the transfer edges from the street graph.
                //           - The new transfer generation do not take this config param into
                //           - account any more. This needs some investigation and probably
                //           - a fix, but we are unsure if this is used any more. The Pathways.txt
                //           - and osm import replaces this functionality.
                gtfsBundle.setTransfersTxtDefinesStationPaths(builderParams.useTransfersTxt);

                if (builderParams.parentStopLinking) {
                    gtfsBundle.linkStopsToParentStations = true;
                }
                gtfsBundle.parentStationTransfers = builderParams.stationTransfers;
                gtfsBundle.subwayAccessTime = (int)(builderParams.subwayAccessTime * 60);
                gtfsBundle.maxInterlineDistance = builderParams.maxInterlineDistance;
                gtfsBundles.add(gtfsBundle);
            }
            GtfsModule gtfsModule = new GtfsModule(gtfsBundles);
            gtfsModule.setFareServiceFactory(builderParams.fareServiceFactory);
            graphBuilder.addModule(gtfsModule);
        }

        if( hasNetex ) {
            graphBuilder.addModule(netexModule(builderParams, netexFiles));
        }

        if(hasTransitData && hasOsm) {
            if (builderParams.matchBusRoutesToStreets) {
                graphBuilder.addModule(new BusRouteStreetMatcher());
            }
            graphBuilder.addModule(new TransitToTaggedStopsModule());
        }

        // This module is outside the hasGTFS conditional block because it also links things like bike rental
        // which need to be handled even when there's no transit.
        StreetLinkerModule streetLinkerModule = new StreetLinkerModule();
        streetLinkerModule.setAddExtraEdgesToAreas(builderParams.areaVisibility);
        graphBuilder.addModule(streetLinkerModule);
        // Load elevation data and apply it to the streets.
        // We want to do run this module after loading the OSM street network but before finding transfers.
        if (builderParams.elevationBucket != null) {
            // Download the elevation tiles from an Amazon S3 bucket
            S3BucketConfig bucketConfig = builderParams.elevationBucket;
            File cacheDirectory = new File(params.cacheDirectory, "ned");
            DegreeGridNEDTileSource awsTileSource = new DegreeGridNEDTileSource();
            awsTileSource.awsAccessKey = bucketConfig.accessKey;
            awsTileSource.awsSecretKey = bucketConfig.secretKey;
            awsTileSource.awsBucketName = bucketConfig.bucketName;
            NEDGridCoverageFactoryImpl gcf = new NEDGridCoverageFactoryImpl(cacheDirectory);
            gcf.tileSource = awsTileSource;
            GraphBuilderModule elevationBuilder = new ElevationModule(
                    gcf,
                    builderParams.elevationUnitMultiplier,
                    builderParams.distanceBetweenElevationSamples
            );
            graphBuilder.addModule(elevationBuilder);
        } else if (builderParams.fetchElevationUS) {
            // Download the elevation tiles from the official web service
            File cacheDirectory = new File(params.cacheDirectory, "ned");
            ElevationGridCoverageFactory gcf = new NEDGridCoverageFactoryImpl(cacheDirectory);
            GraphBuilderModule elevationBuilder = new ElevationModule(
                    gcf,
                    builderParams.elevationUnitMultiplier,
                    builderParams.distanceBetweenElevationSamples
            );
            graphBuilder.addModule(elevationBuilder);
        } else if (demFile != null) {
            // Load the elevation from a file in the graph inputs directory
            ElevationGridCoverageFactory gcf = new GeotiffGridCoverageFactoryImpl(demFile);
            GraphBuilderModule elevationBuilder = new ElevationModule(
                    gcf,
                    builderParams.elevationUnitMultiplier,
                    builderParams.distanceBetweenElevationSamples
            );
            graphBuilder.addModule(elevationBuilder);
        }
        if ( hasTransitData ) {
            // The stops can be linked to each other once they are already linked to the street network.
            if ( ! builderParams.useTransfersTxt) {
                // This module will use streets or straight line distance depending on whether OSM data is found in the graph.
                graphBuilder.addModule(new DirectTransferGenerator(builderParams.maxTransferDistance));
            }
            // Analyze routing between stops to generate report
            if (OTPFeature.TransferAnalyzer.isOn()) {
                graphBuilder.addModule(new DirectTransferAnalyzer(builderParams.maxTransferDistance));
            }
        }
        graphBuilder.addModule(new EmbedConfig(config.builderConfig(), config.routerConfig()));
        if (builderParams.dataImportReport) {
            graphBuilder.addModule(new DataImportIssuesToHTML(params.getGraphDirectory(), builderParams.maxDataImportIssuesPerFile));
        }
        graphBuilder.serializeGraph = !params.inMemory;
        return graphBuilder;
    }

    /**
     * Represents the different types of files that might be present in a router / graph build directory.
     * We want to detect even those that are not graph builder inputs so we can effectively warn when unrecognized file
     * types are present. This helps point out when config files have been misnamed (builder-config vs. build-config).
     */
    private enum InputFileType {
        GTFS, OSM, DEM, CONFIG, GRAPH, NETEX, OTHER;

        static InputFileType forFile(File file, GraphBuilderParameters buildConfig) {
            String name = file.getName();
            if (name.endsWith(".zip")) {
                try {
                    ZipFile zip = new ZipFile(file);
                    ZipEntry stopTimesEntry = zip.getEntry("stop_times.txt");
                    zip.close();
                    if (stopTimesEntry != null) return GTFS;
                } catch (Exception e) { /* fall through */ }
            }
            if (buildConfig.netex.moduleFileMatches(name)) return NETEX;
            if (name.endsWith(".pbf")) return OSM;
            if (name.endsWith(".osm")) return OSM;
            if (name.endsWith(".osm.xml")) return OSM;
            if (name.endsWith(".tif") || name.endsWith(".tiff")) return DEM; // Digital elevation model (elevation raster)
            if (name.equals("Graph.obj")) return GRAPH;
            if (GraphConfig.isGraphConfigFile(name)) return CONFIG;
            return OTHER;
        }
    }
}

