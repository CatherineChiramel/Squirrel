package org.aksw.simba.squirrel.sink;

import org.aksw.simba.squirrel.Constants;
import org.aksw.simba.squirrel.data.uri.CrawleableUri;
import org.aksw.simba.squirrel.sink.tripleBased.TripleBasedSink;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.StmtIterator;

/**
 * The interface of a sink used by a worker. It has to be able to handle
 * both---triples and unstructured data. Therefore, it extends
 * {@link TripleBasedSink} as well as {@link UnstructuredDataSink}.
 *
 * @author Michael R&ouml;der (michael.roeder@uni-paderborn.de)
 *
 */
public interface Sink extends TripleBasedSink, UnstructuredDataSink {

    public default void addMetaData(Model model) {
        CrawleableUri uri = new CrawleableUri(Constants.DEFAULT_META_DATA_GRAPH_URI);
        StmtIterator iterator = model.listStatements();
        while (iterator.hasNext()) {
            addTriple(uri, iterator.next().asTriple());
        }
    }
}
