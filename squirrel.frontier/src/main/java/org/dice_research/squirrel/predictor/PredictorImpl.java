package org.dice_research.squirrel.predictor;


import com.google.common.hash.Hashing;
import de.jungblut.math.DoubleVector;
import de.jungblut.math.activation.ActivationFunction;
import de.jungblut.math.activation.SigmoidActivationFunction;
import de.jungblut.math.dense.SingleEntryDoubleVector;
import de.jungblut.math.loss.LogLoss;
import de.jungblut.math.loss.LossFunction;
import de.jungblut.math.minimize.CostGradientTuple;
import de.jungblut.math.sparse.SequentialSparseDoubleVector;
import de.jungblut.nlp.VectorizerUtils;
import de.jungblut.online.minimizer.StochasticGradientDescent;
import de.jungblut.online.minimizer.StochasticGradientDescent.StochasticGradientDescentBuilder;
import de.jungblut.online.ml.FeatureOutcomePair;
import de.jungblut.online.regression.RegressionClassifier;
import de.jungblut.online.regression.RegressionModel;
import de.jungblut.online.regularization.AdaptiveFTRLRegularizer;
import de.jungblut.online.regularization.CostWeightTuple;
import de.jungblut.online.regularization.WeightUpdater;
import org.dice_research.squirrel.Constants;
import org.dice_research.squirrel.data.uri.CrawleableUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.stream.Stream;


public final class PredictorImpl implements Predictor {

    private ActivationFunction activationFunction;
    private LossFunction lossFunction;
    public WeightUpdater updater;

    public RegressionLearn learner;


    private static final Logger LOGGER = LoggerFactory.getLogger(PredictorImpl.class);
    private static final double beta = 0;
    private static final double l1 = 0;
    private static final double l2 = 0;

    private FeatureOutcomePair featureOutcome;
    private DoubleVector features;

    protected CrawleableUri uri;

    public RegressionModel model;
    public RegressionClassifier classifier;
    public String TRAINING_SET_PATH = "trainDataSet.txt";
    private static final SingleEntryDoubleVector POSITIVE_CLASS = new SingleEntryDoubleVector(1d);
    private static final SingleEntryDoubleVector NEGATIVE_CLASS = new SingleEntryDoubleVector(0d);


    @Override
    public void featureHashing(CrawleableUri uri)  {
        ArrayList<String> tokens1 = new ArrayList<String>();
        tokens1 = tokenCreation(uri, tokens1);
        CrawleableUri referUri;
        if(uri.getData(Constants.REFERRING_URI) != null) {
            referUri = new CrawleableUri((URI) uri.getData(Constants.REFERRING_URI));
            if (referUri != null)
                tokens1 = tokenCreation(referUri, tokens1);
        }
        String[] tokens = new String[tokens1.size()];
        for(int i =0; i<tokens1.size(); i++){
            tokens[i] = tokens1.get(i);
        }

        try {
            DoubleVector feature = VectorizerUtils.sparseHashVectorize(tokens, Hashing.murmur3_128(), () -> new SequentialSparseDoubleVector(
                2<<7));
            double[] d;
            d = feature.toArray();
            uri.addData(Constants.FEATURE_VECTOR, d);
        }catch (Exception e){
            LOGGER.info("Exception caused while adding the feature vector to the URI map"+e);
        }

    }

    public ArrayList tokenCreation(CrawleableUri uri, ArrayList tokens){

        String authority, scheme, userInfo, host, path, query, fragment;
        URI furi = null;
        try {
            furi = new URI(uri.getUri().toString());
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        if (furi != null) {
            authority = furi.getAuthority();
            if(authority != null)
                tokens.add(authority);
            scheme = furi.getScheme();
            if(scheme != null)
                tokens.add(scheme);
            userInfo = furi.getUserInfo();
            if(userInfo != null)
                tokens.add(userInfo);
            host = furi.getHost();
            if(host != null)
                tokens.add(host);
            path = furi.getPath();
            if(path != null) ;
                tokens.add(path);
            query = furi.getQuery();
            if(query != null)
                tokens.add(query);
            fragment = furi.getFragment();
            if(fragment != null)
                tokens.add(fragment);
        }
        return tokens;
    }


    @Override
    public void train() {
        updater = new AdaptiveFTRLRegularizer(1, 1, 1);
        StochasticGradientDescent sgd = StochasticGradientDescentBuilder
            .create(0.01) // learning rate
            .holdoutValidationPercentage(0.05d) // 5% as validation set
            .historySize(10_000) // keep 10k samples to compute relative improvement
            .weightUpdater(updater) // FTRL updater
            .progressReportInterval(1_000) // report every n iterations
            .build();

        // simple regression with Sigmoid and LogLoss
        learner = new RegressionLearn(sgd,
            new SigmoidActivationFunction(), new LogLoss());
        learner.setNumPasses(2);
        learner.verbose();
        // train the model
        this.model = learner.train(() -> setupStream());
        // output the weights
        //model.getWeights().iterateNonZero().forEachRemaining(System.out::println);

    }

    private Stream<FeatureOutcomePair> setupStream(){

        BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getClassLoader().getResourceAsStream(TRAINING_SET_PATH)
            , Charset.defaultCharset()));
        return reader.lines().map((s) -> parseFeature(s));
    }

    private FeatureOutcomePair parseFeature(String line) {
        String[] split = line.split(",");

        URI furi = null;
        try {
            furi = new URI(split[1]);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        CrawleableUri uri = new CrawleableUri(furi);
        featureHashing(uri);
        Object featureArray = uri.getData(Constants.FEATURE_VECTOR);
        double[] doubleFeatureArray = (double[]) featureArray;
        DoubleVector features = new SequentialSparseDoubleVector(doubleFeatureArray);
        return new FeatureOutcomePair(features, split[0].equals("RDF")? POSITIVE_CLASS : NEGATIVE_CLASS);
    }

    @Override
    public double predict(CrawleableUri uri) {
        double pred = 0.0;
        try {
            //Get the feature vector
            if (uri.getData(Constants.FEATURE_VECTOR) != null) {
                Object featureArray = uri.getData(Constants.FEATURE_VECTOR);
                double[] doubleFeatureArray = (double[]) featureArray;
                DoubleVector features = new SequentialSparseDoubleVector(doubleFeatureArray);
                //initialize the regression classifier with updated model and predict
                classifier = new RegressionClassifier(model);
                DoubleVector prediction = classifier.predict(features);
                double[] predictVal = prediction.toArray();
                pred = predictVal[0];
            }else {
                LOGGER.info("Feature vector of this "+ uri.getUri().toString() +" is null");
            }
        } catch (Exception e) {
            LOGGER.warn("Prediction for this "+ uri.getUri().toString() +" failed " + e);
            pred = 0.0;
        }
        return  pred ;
    }

    @Override
    public void weightUpdate(CrawleableUri curi) {
        // Learning Rate used at runtime
        double learningRate = 0.7;
        try {
            if (curi.getData(Constants.FEATURE_VECTOR) != null && curi.getData(Constants.URI_TRUE_LABEL) != null) {
                Object featureArray = curi.getData(Constants.FEATURE_VECTOR);
                double[] doubleFeatureArray = (double[]) featureArray;
                DoubleVector features = new SequentialSparseDoubleVector(doubleFeatureArray);

                Object real_value = curi.getData(Constants.URI_TRUE_LABEL);
                int rv = (int) real_value;
                DoubleVector rv_DoubleVector = new SingleEntryDoubleVector(rv);

                DoubleVector nextExample = features;
                FeatureOutcomePair realResult = new FeatureOutcomePair(nextExample, rv_DoubleVector);// realoutcome
                CostGradientTuple observed = this.learner.observeExample(realResult, this.model.getWeights());

                // calculate new weights (note that the iteration count is not used)
                CostWeightTuple update = this.updater.computeNewWeights(this.model.getWeights(), observed.getGradient(), learningRate, 0, observed.getCost());

                //update weights using the updated parameters
                DoubleVector new_weights = this.updater.prePredictionWeightUpdate(realResult, update.getWeight(),learningRate,0);

                // update model and classifier
                this.model = new RegressionModel(new_weights, this.model.getActivationFunction());

            } else {
                LOGGER.info("Feature vector or true label of this " + curi.getUri().toString() + " is null");
            }
        } catch (Exception e) {
            LOGGER.info("Error while updating the weight " + e);
        }
    }


}
