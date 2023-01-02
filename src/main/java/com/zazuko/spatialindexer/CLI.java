package com.zazuko.spatialindexer;

import org.apache.jena.atlas.io.IOX;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.configuration.GeoSPARQLOperations;
import org.apache.jena.geosparql.configuration.SrsException;
import org.apache.jena.geosparql.spatial.SpatialIndex;
import org.apache.jena.geosparql.spatial.SpatialIndexException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.locationtech.jts.geom.Envelope;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@picocli.CommandLine.Command(name = "index", mixinStandardHelpOptions = true)
public class CLI implements Callable<Integer> {

//  class CommonParams {
    @CommandLine.Option(names = {"--loc"}, paramLabel = "PATH", description = "path to TDB2 dataset", required = true)
    String datasetPath;

    @CommandLine.Option(names = {"i", "--index"}, paramLabel = "FILE", description = "spatial index file", required = true)
    File spatialIndexFile;

    @CommandLine.Option(names = {"--srs"}, paramLabel = "URI", description = "SRS URI used during indexing")
    String srsURI;
//  }

  @CommandLine.Option(names = {"--overwrite"}, description = "overwrite spatial index if exists")
  boolean overwrite;

  @CommandLine.Option(names = {"--index-per-graph"}, description = "if enabled, an index tree per graph will be generated", defaultValue = "true")
  boolean indexPerGraph = true;

  @CommandLine.Option(names = {"-g", "--graph"}, paramLabel = "URI", description = "named graph URI", split = ",")
  String[] graphURIs;


  public static void main(String[] args) {
    int exitCode = new CommandLine(new CLI()).execute(args);
    System.exit(exitCode);
  }

  public Integer call() throws Exception {
    final Dataset dataset = TDB2Factory.connectDataset(datasetPath);

    if (spatialIndexFile.exists()) {
      System.out.println("Spatial index already exists.");
    }

    try {
      // determine SRS if not provided
      if (srsURI == null) {
        srsURI = GeoSPARQLOperations.findModeSRS(dataset);
        System.out.println("most prominent SRS URI: " + srsURI);
      }


      if (overwrite) {// overwrite mode, we write to tmp file first, then, if successful, replace the index file
        String filename = spatialIndexFile.getAbsolutePath();
        Path file = Path.of(filename);
        Path tmpFile = IOX.uniqueDerivedPath(file, null);
        try {
          Files.deleteIfExists(file);
        } catch (IOException ex) {
          throw new SpatialIndexException("Failed to delete file: " + ex.getMessage());
        }
        SpatialIndex.buildSpatialIndex(dataset, srsURI, tmpFile.toFile(), indexPerGraph);
        IOX.moveAllowCopy(tmpFile, spatialIndexFile.toPath());
      } else {
        SpatialIndex.buildSpatialIndex(dataset, srsURI, spatialIndexFile, indexPerGraph);
      }

    } catch (SpatialIndexException e) {
      System.err.println(e);
      return 1;
    }
    return 0;
  }

  @CommandLine.Command(name = "replace", description = "replace existing indexed named graphs")
  int replace(@CommandLine.Option(names = {"-g", "--graph"}) String graph) {
    final Dataset dataset = TDB2Factory.connectDataset(datasetPath);

    if (!spatialIndexFile.exists()) {
      System.out.println("Spatial index does not exists.");
      return 0;
    }

    try {
      SpatialIndex index = SpatialIndex.load(spatialIndexFile);

      SpatialIndex.recomputeIndexForGraphs(index, dataset, List.of(graph));

      SpatialIndex.save(spatialIndexFile, index);
    } catch (SpatialIndexException e) {
      System.err.println(e);
      return 1;
    }
    return 0;
  }

  @CommandLine.Command(name = "stats", description = "returns some statistics of the spatial index")
  int stats(@CommandLine.Option(names = {"-g", "--graph"}) String graph) {
    final Dataset dataset = TDB2Factory.connectDataset(datasetPath);

    if (!spatialIndexFile.exists()) {
      System.out.println("Spatial index does not exists.");
      return 0;
    }

    try {
      SpatialIndex index = SpatialIndex.load(spatialIndexFile);

      Envelope envelope = index.getSrsInfo().getDomainEnvelope();


      int size = index.getDefaultGraphIndexTree().query(envelope).size();
      System.out.println("default graph: " + size);

      index.getNamedGraphToIndexTreeMapping().forEach((g, tree) -> {
        System.out.println(g + ":" + tree.query(envelope).size());
      });
    } catch (SpatialIndexException e) {
      System.err.println(e);
      return 1;
    }
    return 0;
  }
}