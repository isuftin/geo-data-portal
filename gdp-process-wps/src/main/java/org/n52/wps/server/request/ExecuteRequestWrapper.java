package org.n52.wps.server.request;

import gov.usgs.cida.gdp.wps.analytics.MetadataObserver;
import gov.usgs.cida.gdp.wps.queue.ExecuteRequestManager;
import gov.usgs.cida.gdp.wps.queue.ThrottleStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.opengis.wps.x100.ExecuteDocument;
import net.opengis.wps.x100.InputType;
import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.n52.wps.commons.context.ExecutionContext;
import org.n52.wps.commons.context.ExecutionContextFactory;
import org.n52.wps.io.data.IComplexData;
import org.n52.wps.io.data.IData;
import org.n52.wps.server.AbstractTransactionalAlgorithm;
import org.n52.wps.server.ExceptionReport;
import org.n52.wps.server.IAlgorithm;
import org.n52.wps.server.RepositoryManager;
import org.n52.wps.server.observerpattern.ISubject;
import org.n52.wps.server.response.ExecuteResponse;
import org.n52.wps.server.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 *
 * @author smlarson
 */
public class ExecuteRequestWrapper extends ExecuteRequest {

    private static Logger LOGGER_WRAPPER = LoggerFactory.getLogger(ExecuteRequestWrapper.class);
    private ExecuteDocument execDom;

    public ExecuteRequestWrapper(CaseInsensitiveMap ciMap) throws ExceptionReport {
        super(ciMap);
    }

    public ExecuteRequestWrapper(Document doc) throws ExceptionReport {
        super(doc);
    }

    /**
     * Actually serves the Request.
     *
     * @return
     * @throws ExceptionReport
     */
    @Override
    public Response call() throws ExceptionReport {
        IAlgorithm algorithm = null;
        Map<String, List<IData>> inputMap = null;
        MetadataObserver metaObs = new MetadataObserver(this.getUniqueId());

        LOGGER_WRAPPER.info("PROCESSING in call of ExecuteRequestWrapper. reqId: " + this.getUniqueId());
        try {
            ExecutionContext context;
            if (getExecute().isSetResponseForm()) {
                context = getExecute().getResponseForm().isSetRawDataOutput()
                        ? new ExecutionContext(getExecute().getResponseForm().getRawDataOutput())
                        : new ExecutionContext(Arrays.asList(getExecute().getResponseForm().getResponseDocument().getOutputArray()));
            } else {
                context = new ExecutionContext();
            }
            // register so that any function that calls ExecuteContextFactory.getContext() gets the instance registered with this thread
            ExecutionContextFactory.registerContext(context);

            LOGGER_WRAPPER.debug("started with execution");

            updateStatusStarted();
            // #USGS want to maintain an internal hashmap or DB representation of everything that is on this VMs queue 
            ExecuteRequestManager.getInstance().getThrottleQueue().updateStatus(this, ThrottleStatus.STARTED);
            //insertThrottleQueueStatus(this.getUniqueId().toString()); //#USGS override code  - do I need this anymore?

            // parse the input
            InputType[] inputs = new InputType[0];
            if (getExecute().getDataInputs() != null) {
                inputs = getExecute().getDataInputs().getInputArray();
            }
            InputHandler parser = new InputHandler.Builder(inputs, getAlgorithmIdentifier()).build();

            // we got so far:
            // get the algorithm, and run it with the clients input
            /*
			 * IAlgorithm algorithm =
			 * RepositoryManager.getInstance().getAlgorithm(getAlgorithmIdentifier());
			 * returnResults = algorithm.run((Map)parser.getParsedInputLayers(),
			 * (Map)parser.getParsedInputParameters());
             */
            algorithm = RepositoryManager.getInstance().getAlgorithm(getAlgorithmIdentifier());

            if (algorithm instanceof ISubject) {
                ISubject subject = (ISubject) algorithm;
                subject.addObserver(this);
                subject.addObserver(metaObs);
            }

            if (algorithm instanceof AbstractTransactionalAlgorithm) {
                returnResults = ((AbstractTransactionalAlgorithm) algorithm).run(execDom);
            } else {
                inputMap = parser.getParsedInputData();
                returnResults = algorithm.run(inputMap);
            }

            List<String> errorList = algorithm.getErrors();
            if (errorList != null && !errorList.isEmpty()) {
                String errorMessage = errorList.get(0);
                LOGGER_WRAPPER.error("Error reported while handling ExecuteRequest for " + getAlgorithmIdentifier() + ": " + errorMessage + " with requestId " + this.getUniqueId());
                updateStatusError(errorMessage);
                // #USGS# remove the request from the hashmap / DB throttle_queue with status of processed
                ExecuteRequestManager.getInstance().getThrottleQueue().removeRequest(this.getUniqueId().toString());
            } else {
                updateStatusSuccess();
                LOGGER_WRAPPER.info("Status update was successful");
                // #USGS# remove the request from the hashmap / DB throttle_queue with status of processed
                ExecuteRequestManager.getInstance().getThrottleQueue().removeRequest(this.getUniqueId().toString());
            }

        } catch (Throwable e) {
            String errorMessage = null;
            if (algorithm != null && algorithm.getErrors() != null && !algorithm.getErrors().isEmpty()) {
                errorMessage = algorithm.getErrors().get(0);
            }
            if (errorMessage == null) {
                errorMessage = e.toString();
            }
            if (errorMessage == null) {
                errorMessage = "UNKNOWN ERROR";
            }
            LOGGER_WRAPPER.error("Exception/Error while executing ExecuteRequest for " + getAlgorithmIdentifier() + ": " + errorMessage);
            updateStatusError(errorMessage);
            if (e instanceof Error) {
                // This is required when catching Error
                throw (Error) e;
            }
            if (e instanceof ExceptionReport) {
                throw (ExceptionReport) e;
            } else {
                throw new ExceptionReport("Error while executing the embedded process for: " + getAlgorithmIdentifier(), ExceptionReport.NO_APPLICABLE_CODE, e);
            }
        } finally {
            //  you ***MUST*** call this or else you will have a PermGen ClassLoader memory leak due to ThreadLocal use
            ExecutionContextFactory.unregisterContext();
            if (algorithm instanceof ISubject) {
                ISubject subject = (ISubject) algorithm;
                subject.removeObserver(this);
                subject.removeObserver(metaObs);
            }
            if (inputMap != null) {
                for (List<IData> l : inputMap.values()) {
                    for (IData d : l) {
                        if (d instanceof IComplexData) {
                            ((IComplexData) d).dispose();
                        }
                    }
                }
            }
            if (returnResults != null) {
                for (IData d : returnResults.values()) {
                    if (d instanceof IComplexData) {
                        ((IComplexData) d).dispose();
                    }
                }
            }

        }

        ExecuteResponse response = new ExecuteResponse(this);
        return response;
    }

    // during the serialization of the Request for the throttle queue, the id is set on the re-constructed request from the DB 
    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public void update(ISubject subject) {
        /* We will handle percent complete updates via MetadataObserver
         * This needs to cancel out the super class which fails if subject is
         * not Integer or String.
         *
         * In regular use, the super class should ignore subjects it can't handle
         * rather than perform a botched update (currently ProcessFailed)
         */
    }

}
