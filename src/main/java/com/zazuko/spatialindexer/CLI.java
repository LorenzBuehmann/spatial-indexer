package com.zazuko.spatialindexer;

import de.vandermeer.asciitable.AT_Row;
import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestLine;
import de.vandermeer.skb.interfaces.transformers.textformat.TextAlignment;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

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

  @CommandLine.Option(names = {"--index-per-graph"}, description = "if enabled, an index tree per graph will be generated (default: ${DEFAULT-VALUE})", defaultValue = "true")
  boolean indexPerGraph = true;

  @CommandLine.Option(names = {"-g", "--graph"}, paramLabel = "URI", description = "named graph URI", split = ",")
  String[] graphURIs;

  @CommandLine.Option(names = {"--resource-serde"}, description = "resource serialization: ${COMPLETION-CANDIDATES}")
  ResourceSerde resourceSerde;

  @CommandLine.Option(names = {"--index-serde"}, description = "index serialization: ${COMPLETION-CANDIDATES}")
  IndexSerde indexSerde;

  enum ResourceSerde {
    STRING, NODE
  }

  enum IndexSerde {
    JAVA, KRYO
  }


  public static void main(String[] args) {
    int exitCode = new CommandLine(new CLI()).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
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

      if (resourceSerde == ResourceSerde.NODE) {
        SpatialIndex.resourceSerde=res -> res.asNode();
      } else {
        SpatialIndex.resourceSerde=res -> res.asNode().toString();
      }
      SpatialIndex index = SpatialIndex.buildSpatialIndex(dataset, srsURI, indexPerGraph);//, tmpFile.toFile(), indexPerGraph);


      if (overwrite) {// overwrite mode, we write to tmp file first, then, if successful, replace the index file
        String filename = spatialIndexFile.getAbsolutePath();
        Path file = Path.of(filename);
        Path tmpFile = IOX.uniqueDerivedPath(file, null);
        try {
          Files.deleteIfExists(file);
        } catch (IOException ex) {
          throw new SpatialIndexException("Failed to delete file: " + ex.getMessage());
        }

        if (indexSerde == IndexSerde.KRYO) {
          SpatialIndex.save(tmpFile.toFile(), index);
        } else {
          SpatialIndex.saveWithJavaSerde(tmpFile.toFile(), index);
        }

        IOX.moveAllowCopy(tmpFile, spatialIndexFile.toPath());
      } else {
        if (indexSerde == IndexSerde.KRYO) {
          SpatialIndex.save(spatialIndexFile, index);
        } else {
          SpatialIndex.saveWithJavaSerde(spatialIndexFile, index);
        }
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

      Map<String, Integer> graphToSize = new TreeMap<>();
      Map<String, Integer> graphToDepth = new TreeMap<>();

      graphToSize.put("DEFAULT", index.getDefaultGraphIndexTree().size());
      graphToDepth.put("DEFAULT", index.getDefaultGraphIndexTree().depth());


      Map<String, Integer> map = index.getNamedGraphToIndexTreeMapping().entrySet()
        .parallelStream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
      graphToSize.putAll(map);

      map = index.getNamedGraphToIndexTreeMapping().entrySet()
        .parallelStream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().depth()));
      graphToDepth.putAll(map);

      AsciiTable at = new AsciiTable();
      CWC_LongestLine cwc = new CWC_LongestLine();

      at.getRenderer().setCWC(cwc);
      at.addRule();
      AT_Row header = at.addRow("Graph", "Size", "Depth");
      header.setTextAlignment(TextAlignment.CENTER);
      at.addRule();
      graphToSize.forEach((g, size) -> {
        AT_Row row = at.addRow(g, size, graphToDepth.get(g));
        row.getCells().get(0).getContext().setPaddingLeftRight(1);
        row.getCells().get(1).getContext().setTextAlignment(TextAlignment.RIGHT);
        row.getCells().get(2).getContext().setTextAlignment(TextAlignment.RIGHT);
      });

      at.addRule();

      System.out.println(at.render());

    } catch (SpatialIndexException e) {
      System.err.println(e);
      return 1;
    }
    return 0;
  }
}
