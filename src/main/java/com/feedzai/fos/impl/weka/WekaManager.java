/*
 * $#
 * FOS Weka
 *  
 * Copyright (C) 2013 Feedzai SA
 *  
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #$
 */
package com.feedzai.fos.impl.weka;

import au.com.bytecode.opencsv.CSVReader;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CollectionSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedzai.fos.api.*;
import com.feedzai.fos.common.kryo.CustomUUIDSerializer;
import com.feedzai.fos.common.kryo.ScoringRequestEnvelope;
import com.feedzai.fos.common.validation.NotBlank;
import com.feedzai.fos.common.validation.NotNull;
import com.feedzai.fos.impl.weka.config.WekaManagerConfig;
import com.feedzai.fos.impl.weka.config.WekaModelConfig;
import com.feedzai.fos.impl.weka.utils.WekaUtils;
import com.feedzai.fos.impl.weka.utils.setter.InstanceSetter;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.classifiers.Classifier;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class implements a manager that is able to train and score
 * using Weka classifiers.
 * <p/>
 * Aditionally, it also implements a Kryo endpoint for scoring to be used along
 * KryoScorer.
 *
 * @author Marco Jorge (marco.jorge@feedzai.com)
 * @author Miguel Duarte (miguel.duarte@feedzai.com)
 */
public class WekaManager implements Manager {
    private final static Logger logger = LoggerFactory.getLogger(WekaManager.class);
    private Thread acceptThread;
    private ServerSocket serverSocket;
    ObjectMapper mapper = new ObjectMapper();

    private Map<UUID, WekaModelConfig> modelConfigs = new HashMap<>();
    private WekaManagerConfig wekaManagerConfig;
    private WekaScorer wekaScorer;
    private KryoScoringEndpoint scorerHandler;

    private volatile boolean acceptThreadRunning = false;


    /**
     * Save dirty configurations to disk.
     * <p/> If saving configuration was not possible, a log is produced but no exception is thrown.
     */
    private synchronized void saveConfiguration() {
        for (WekaModelConfig wekaModelConfig : modelConfigs.values()) {
            if (wekaModelConfig.isDirty() && wekaModelConfig.getModelConfig().isStoreModel()) {
                try {
                    String modelConfigJson = mapper.writeValueAsString(wekaModelConfig.getModelConfig());

                    // create a new file because this model has never been written
                    if (wekaModelConfig.getHeader() == null) {
                        File file = File.createTempFile(wekaModelConfig.getId().toString(), "." + WekaManagerConfig.HEADER_EXTENSION, wekaManagerConfig.getHeaderLocation());
                        wekaModelConfig.setHeader(file);
                    }

                    FileUtils.write((wekaModelConfig).getHeader(), modelConfigJson);
                    wekaModelConfig.setDirty(false /* contents have been updated so the model is no longer dirty*/);
                } catch (IOException e) {
                    logger.error("Could not store configuration for model '{}' (will continue to save others)", wekaModelConfig.getId(), e);
                }
            }
        }
    }

    /**
     * Create a new manager from the given configuration.
     * <p/> Will lookup any headers files and to to instantiate the model.
     * <p/> If a model fails, a log is produced but loading other models will continue (no exception is thrown).
     *
     * @param wekaManagerConfig the manager configuration
     */
    public WekaManager(WekaManagerConfig wekaManagerConfig) {
        checkNotNull(wekaManagerConfig, "Manager config cannot be null");

        this.wekaManagerConfig = wekaManagerConfig;

        Collection<File> headers = FileUtils.listFiles(wekaManagerConfig.getHeaderLocation(), new String[]{WekaManagerConfig.HEADER_EXTENSION}, true);
        for (File header : headers) {
            logger.trace("Reading model file '{}'", header);

            FileInputStream fileInputStream = null;
            try {
                fileInputStream = new FileInputStream(header);
                String modelConfigJson = IOUtils.toString(fileInputStream);

                ModelConfig modelConfig = mapper.readValue(modelConfigJson, ModelConfig.class);
                WekaModelConfig wekaModelConfig = new WekaModelConfig(modelConfig, wekaManagerConfig);
                wekaModelConfig.setHeader(header);
                wekaModelConfig.setDirty(false /* not changed so far */);

                if (modelConfigs.containsKey(wekaModelConfig.getId())) {
                    logger.error("Model with ID '{}' is duplicated in the configuration (the configuration from '{}' is discarded)", wekaModelConfig.getId(), header.getAbsolutePath());
                } else {
                    modelConfigs.put(wekaModelConfig.getId(), wekaModelConfig);
                }
            } catch (Exception e) {
                logger.error("Could not load from '{}' (continuing to load others)", header, e);
            } finally {
                IOUtils.closeQuietly(fileInputStream);
            }
        }

        this.wekaScorer = new WekaScorer(modelConfigs, wekaManagerConfig);

        try {
            int port = wekaManagerConfig.getScoringPort();
            this.serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            final int max_threads = wekaManagerConfig.getMaxSimultaneousScoringThreads();
            Runnable acceptRunnable = new Runnable() {
                ExecutorService executor = Executors.newFixedThreadPool(max_threads);

                @Override
                public void run() {
                    acceptThreadRunning = true;
                    try {
                        while (acceptThreadRunning &&
                                Thread.currentThread().isInterrupted() == false) {
                            Socket client = serverSocket.accept();
                            client.setTcpNoDelay(true);
                            scorerHandler = new KryoScoringEndpoint(client, wekaScorer);
                            executor.submit(scorerHandler);
                        }
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            };
            acceptThread = new Thread(acceptRunnable);
            acceptThread.start();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Persists the model to disk.
     *
     * @param id    the id of the model
     * @param model the serialized classifier
     * @return the File where the model was written
     * @throws IOException if saving to disk was not possible
     */
    private File createModelFile(UUID id, byte[] model) throws IOException {
        File file = File.createTempFile(id.toString(), ".model", wekaManagerConfig.getHeaderLocation());
        FileUtils.writeByteArrayToFile(file, model);
        return file;
    }

    @Override
    public synchronized UUID addModel(ModelConfig config, byte[] model) throws FOSException {
        try {
            UUID uuid = getUuid(config);

            File file = createModelFile(uuid, model);

            WekaModelConfig wekaModelConfig = new WekaModelConfig(config, wekaManagerConfig);
            wekaModelConfig.setId(uuid);
            wekaModelConfig.setModel(file);

            modelConfigs.put(uuid, wekaModelConfig);
            wekaScorer.addOrUpdate(wekaModelConfig);

            saveConfiguration();
            logger.debug("Model {} added", uuid);
            return uuid;
        } catch (IOException e) {
            throw new FOSException(e);
        }
    }

    /**
     * Obtain model UUID from ModelConfig if defined or generate a new random uuid
     *
     * @param config Model Configuration
     * @return new Model UUID
     * @throws FOSException
     */
    private UUID getUuid(ModelConfig config) throws FOSException {
        String suuid = config.getProperty("UUID");
        UUID uuid;
        if (suuid == null) {
            uuid = UUID.randomUUID();
        } else {
            uuid = UUID.fromString(suuid);
        }
        return uuid;
    }

    @Override
    public synchronized UUID addModel(ModelConfig config, @NotBlank String localFileName) throws FOSException {
        UUID uuid = getUuid(config);

        WekaModelConfig wekaModelConfig = new WekaModelConfig(config, wekaManagerConfig);
        wekaModelConfig.setId(uuid);
        wekaModelConfig.setModel(new File(localFileName));

        modelConfigs.put(uuid, wekaModelConfig);
        wekaScorer.addOrUpdate(wekaModelConfig);
        saveConfiguration();
        logger.debug("Model {} added", uuid);
        return uuid;
    }

    @Override
    public synchronized void removeModel(UUID modelId) throws FOSException {
        WekaModelConfig wekaModelConfig = modelConfigs.remove(modelId);
        if (wekaModelConfig == null) {
            logger.warn("Could not remove model with id {} because it does not exists", modelId);
            return;
        }
        wekaScorer.removeModel(modelId);

        if (wekaModelConfig.getModelConfig().isStoreModel()) {

            // delete the header & model file (or else it will be picked up on the next restart)
            wekaModelConfig.getHeader().delete();
            // only delete if is in our header location
            if (!wekaManagerConfig.getHeaderLocation().toURI().relativize(wekaModelConfig.getModel().toURI()).isAbsolute()) {
                wekaModelConfig.getModel().delete();
            }
        }
        logger.debug("Model {} removed", modelId);
    }

    @Override
    public synchronized void reconfigureModel(UUID modelId, ModelConfig modelConfig) throws FOSException {
        WekaModelConfig wekaModelConfig = this.modelConfigs.get(modelId);
        wekaModelConfig.update(modelConfig);

        wekaScorer.addOrUpdate(wekaModelConfig);
        saveConfiguration();
        logger.debug("Model {} reconfigured", modelId);
    }

    @Override
    public synchronized void reconfigureModel(UUID modelId, ModelConfig modelConfig, byte[] model) throws FOSException {
        try {
            File file = createModelFile(modelId, model);

            WekaModelConfig wekaModelConfig = this.modelConfigs.get(modelId);
            wekaModelConfig.update(modelConfig);
            wekaModelConfig.setModel(file);

            wekaScorer.addOrUpdate(wekaModelConfig);
            saveConfiguration();
            logger.debug("Model {} reconfigured", modelId);
        } catch (IOException e) {
            throw new FOSException(e);
        }
    }

    @Override
    public synchronized void reconfigureModel(UUID modelId, ModelConfig modelConfig, @NotBlank String localFileName) throws FOSException {
        File file = new File(localFileName);

        WekaModelConfig wekaModelConfig = this.modelConfigs.get(modelId);
        wekaModelConfig.update(modelConfig);
        wekaModelConfig.setModel(file);

        wekaScorer.addOrUpdate(wekaModelConfig);
        saveConfiguration();
    }

    @Override
    @NotNull
    public synchronized Map<UUID, ModelConfig> listModels() {
        Map<UUID, ModelConfig> result = new HashMap<>(modelConfigs.size());
        for (Map.Entry<UUID, WekaModelConfig> entry : modelConfigs.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getModelConfig());
        }

        return result;
    }

    @Override
    @NotNull
    public WekaScorer getScorer() {
        return wekaScorer;
    }

    @Override
    public synchronized UUID trainAndAdd(ModelConfig config, List<Object[]> instances) throws FOSException {
        byte[] serializedClassifier = train(config, instances);
        return addModel(config, serializedClassifier);
    }

    @Override
    public synchronized UUID trainAndAddFile(ModelConfig config, String path) throws FOSException {
        byte[] serializedClassifier = trainFile(config, path);
        return addModel(config, serializedClassifier);
    }


    @Override
    public byte[] train(ModelConfig config, List<Object[]> instances) throws FOSException {
        checkNotNull(instances, "Instances must not supplied");
        checkNotNull(config, "Config must not be supplied");
        long time = System.currentTimeMillis();
        WekaModelConfig wekaModelConfig = new WekaModelConfig(config, wekaManagerConfig);
        Classifier classifier = WekaClassifierFactory.create(config);
        FastVector attributes = WekaUtils.instanceFields2Attributes(wekaModelConfig.getClassIndex(), config.getAttributes());
        InstanceSetter[] instanceSetters = WekaUtils.instanceFields2ValueSetters(config.getAttributes(), InstanceType.TRAINING);

        Instances wekaInstances = new Instances(config.getProperty(WekaModelConfig.CLASSIFIER_IMPL), attributes, instances.size());

        for (Object[] objects : instances) {
            wekaInstances.add(WekaUtils.objectArray2Instance(objects, instanceSetters, attributes));
        }

        final byte[] bytes = serializeClassifier(wekaModelConfig, classifier, wekaInstances);

        logger.debug("Trained model with {} instances in {}ms", instances.size(), (System.currentTimeMillis() - time));
        return bytes;

    }

    @Override
    public byte[] trainFile(ModelConfig config, String path) throws FOSException {
        checkNotNull(path, "Config must be supplied");
        checkNotNull(path, "Path must be supplied");
        long time = System.currentTimeMillis();
        WekaModelConfig wekaModelConfig = new WekaModelConfig(config, wekaManagerConfig);
        Classifier classifier = WekaClassifierFactory.create(config);
        List<Attribute> attributeList = config.getAttributes();
        FastVector attributes = WekaUtils.instanceFields2Attributes(wekaModelConfig.getClassIndex(), config.getAttributes());
        InstanceSetter[] instanceSetters = WekaUtils.instanceFields2ValueSetters(config.getAttributes(), InstanceType.TRAINING);

        List<Instance> instances = new ArrayList();


        String[] line;
        try {
            FileReader fileReader = new FileReader(path);
            CSVReader csvReader = new CSVReader(fileReader);
            while ((line = csvReader.readNext()) != null) {
                double[] values = new double[line.length];
                for (int i = 0; i != attributeList.size(); ++i) {
                    values[i] = attributeList.get(i).parse(line[i], InstanceType.TRAINING);

                }
                instances.add(WekaUtils.objectArray2Instance(line, instanceSetters, attributes));
            }

        } catch (Exception e) {
            throw new FOSException(e.getMessage(), e);
        }

        Instances wekaInstances = new Instances(config.getProperty(WekaModelConfig.CLASSIFIER_IMPL), attributes, instances.size());

        for (Instance instance : instances) {
            wekaInstances.add(instance);
        }

        final byte[] bytes = serializeClassifier(wekaModelConfig, classifier, wekaInstances);
        logger.debug("Trained model with {} instances in {}ms", instances.size(), (System.currentTimeMillis() - time));
        return bytes;
    }

    /**
     * Will save the configuration to file.
     *
     * @throws FOSException when there are IO problems writing the configuration to file
     */
    @Override
    public synchronized void close() throws FOSException {
        acceptThreadRunning = false;
        if (scorerHandler != null) {
            scorerHandler.running = false;
            scorerHandler.close();
        }

        try {
            serverSocket.close();
        } catch (IOException e) {
            // nothing to do here
        }
        saveConfiguration();
    }

    private byte[] serializeClassifier(WekaModelConfig modelConfig, Classifier classifier, Instances wekaInstances) throws FOSException {
        byte[] serialized;

        try {
            int index = modelConfig.getClassIndex();
            wekaInstances.setClassIndex(index == -1 ? wekaInstances.numAttributes() - 1 : index);
            classifier.buildClassifier(wekaInstances);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(classifier);
            oos.close();
            serialized = bos.toByteArray();

        } catch (Exception e) {
            throw new FOSException(e.getMessage(), e);
        }
        return serialized;
    }


    @Override
    public void save(UUID uuid, String savepath) throws FOSException {
        try {
            File source = modelConfigs.get(uuid).getModel();
            File destination = new File(savepath);
            Files.copy(source, destination);
        } catch (Exception e) {
            throw new FOSException("Unable to save model " + uuid + " to " + savepath, e);
        }
    }

    /**
     * This class should be used to perform scoring requests
     * to a remote FOS instance that suports kryo end points.
     * <p/>
     * It listens on a socket input stream for Kryo serialized {@link ScoringRequestEnvelope}
     * objects, extracts them and forwards them to the local scorer.
     * <p/>
     * The scoring result is then Kryo encoded on the socket output stream.
     */
    private static class KryoScoringEndpoint implements Runnable {
        public static final int BUFFER_SIZE = 1024;
        Socket client;
        Scorer scorer;
        private volatile boolean running = true;

        private KryoScoringEndpoint(Socket client, Scorer scorer) throws IOException {
            this.client = client;
            this.scorer = scorer;
        }

        @Override
        public void run() {
            Kryo kryo = new Kryo();
            kryo.addDefaultSerializer(UUID.class, new CustomUUIDSerializer());
            // workaround for java.util.Arrays$ArrayList missing default constructor
            kryo.register(Arrays.asList().getClass(), new CollectionSerializer() {
                protected Collection create(Kryo kryo, Input input, Class<Collection> type) {
                    return new ArrayList();
                }
            });

            Input input = new Input(BUFFER_SIZE);
            Output output = new Output(BUFFER_SIZE);

            ScoringRequestEnvelope request = null;
            try (InputStream is = client.getInputStream();
                 OutputStream os = client.getOutputStream()) {
                input.setInputStream(is);
                output.setOutputStream(os);
                while (running) {
                    request = kryo.readObject(input, ScoringRequestEnvelope.class);
                    List<double[]> scores = scorer.score(request.getUUIDs(),
                            request.getInstance());
                    kryo.writeObject(output, scores);
                    output.flush();
                    os.flush();
                }
            } catch (Exception e) {
                if (request != null) {
                    logger.error("Error scoring instance {} for models {}", Arrays.toString(request.getInstance()), Arrays.toString(request.getUUIDs().toArray()), e);
                } else {
                    logger.error("Error scoring instance", e);
                }
            } finally {
                try {
                    client.close();
                } catch (IOException e) {
                    // nothing to do here
                }
                running = false;
            }
        }

        public void close() {
            try {
                running = false;
                client.close();
            } catch (IOException e) {
                // nothing to do here
            }
        }
    }
}