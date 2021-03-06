/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.rtgov.ui.provider.situations;

import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.tryFind;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.overlord.commons.services.ServiceRegistryUtil;
import org.overlord.rtgov.active.collection.ActiveChangeListener;
import org.overlord.rtgov.active.collection.ActiveCollection;
import org.overlord.rtgov.active.collection.ActiveCollectionListener;
import org.overlord.rtgov.active.collection.ActiveCollectionManager;
import org.overlord.rtgov.active.collection.ActiveCollectionManagerAccessor;
import org.overlord.rtgov.active.collection.ActiveList;
import org.overlord.rtgov.activity.model.ActivityType;
import org.overlord.rtgov.activity.model.ActivityTypeId;
import org.overlord.rtgov.activity.model.ActivityUnit;
import org.overlord.rtgov.activity.model.Context;
import org.overlord.rtgov.activity.model.soa.RPCActivityType;
import org.overlord.rtgov.activity.server.ActivityServer;
import org.overlord.rtgov.activity.server.ActivityStore;
import org.overlord.rtgov.activity.server.ActivityStoreFactory;
import org.overlord.rtgov.activity.server.QuerySpec;
import org.overlord.rtgov.analytics.situation.Situation;
import org.overlord.rtgov.ui.client.model.ResolutionState;
import org.overlord.rtgov.analytics.situation.store.SituationStore;
import org.overlord.rtgov.analytics.situation.store.SituationStoreFactory;
import org.overlord.rtgov.analytics.situation.store.SituationsQuery;
import org.overlord.rtgov.call.trace.CallTraceService;
import org.overlord.rtgov.call.trace.CallTraceServiceImpl;
import org.overlord.rtgov.call.trace.model.Call;
import org.overlord.rtgov.call.trace.model.CallTrace;
import org.overlord.rtgov.call.trace.model.Task;
import org.overlord.rtgov.call.trace.model.TraceNode;
import org.overlord.rtgov.common.util.RTGovProperties;
import org.overlord.rtgov.ui.client.model.BatchRetryResult;
import org.overlord.rtgov.ui.client.model.CallTraceBean;
import org.overlord.rtgov.ui.client.model.MessageBean;
import org.overlord.rtgov.ui.client.model.SituationBean;
import org.overlord.rtgov.ui.client.model.SituationEventBean;
import org.overlord.rtgov.ui.client.model.SituationSummaryBean;
import org.overlord.rtgov.ui.client.model.SituationsFilterBean;
import org.overlord.rtgov.ui.client.model.TraceNodeBean;
import org.overlord.rtgov.ui.client.model.UiException;
import org.overlord.rtgov.ui.provider.ResubmitActionProvider;
import org.overlord.rtgov.ui.provider.ServicesProvider;
import org.overlord.rtgov.ui.provider.SituationEventListener;
import org.overlord.rtgov.ui.provider.SituationsProvider;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;

/**
 * Concrete implementation of the faults service using RTGov situations.
 *
 */
public class RTGovSituationsProvider implements SituationsProvider, ActiveChangeListener {

    private static final String PROVIDER_NAME = "rtgov"; //$NON-NLS-1$
	
	// Active collection name
	private static final String SITUATIONS = "Situations"; //$NON-NLS-1$
	
    protected static final int MILLISECONDS_PER_DAY = 86400000;

	private static volatile Messages i18n = new Messages();
	
	private CallTraceService _callTraceService=new CallTraceServiceImpl();

	private ActivityStore _activityStore;

	private SituationStore _situationStore;

	private java.util.Set<ServicesProvider> _providers=null;
	
	private java.util.List<SituationEventListener> _listeners=new java.util.ArrayList<SituationEventListener>();

	private ActiveList _situations;
	private ActiveCollectionManager _acmManager;
	
    /**
     * Constructor.
     */
    public RTGovSituationsProvider() {
    }
    
    /**
     * This method returns the set of services providers.
     * 
     * @return The providers
     */
    protected java.util.Set<ServicesProvider> getProviders() {
    	return (_providers);
    }
    
    /**
     * This method sets the activity store.
     * 
     * @param acts The activity store
     */
    protected void setActivityStore(ActivityStore acts) {
    	_activityStore = acts;
    }
    
    /**
     * This method returns the activity store.
     * 
     * @return The activity store
     */
    protected ActivityStore getActivityStore() {
    	return (_activityStore);
    }

    /**
     * This method sets the situation store.
     * 
     * @param sits The situation store
     */
    protected void setSituationStore(SituationStore sits) {
    	_situationStore = sits;
    }
    
    /**
     * This method returns the situation store.
     * 
     * @return The situation store
     */
    protected SituationStore getSituationStore() {
    	return (_situationStore);
    }

    /**
     * This method sets the call trace service.
     * 
     * @param cts The call trace service
     */
    protected void setCallTraceService(CallTraceService cts) {
    	_callTraceService = cts;
    }
    
    /**
     * This method returns the call trace service.
     * 
     * @return The call trace service
     */
    protected CallTraceService getCallTraceService() {
    	return (_callTraceService);
    }

    /**
     * This method sets the situations active list.
     * 
     * @param cts The situations active list
     */
    protected void setSituations(ActiveList situations) {
    	_situations = situations;
    }
    
    /**
     * This method returns the situations active list.
     * 
     * @return The situations active list
     */
    protected ActiveList getSituations() {
    	return (_situations);
    }

    @PostConstruct
    public void init() {
        
        if (_activityStore == null) {
            _activityStore = ActivityStoreFactory.getActivityStore();
        }
        
        if (_situationStore == null) {
            _situationStore = SituationStoreFactory.getSituationStore();
        }
        
    	_providers = ServiceRegistryUtil.getServices(ServicesProvider.class); 
    	
    	if (_callTraceService != null) {
    		// Overwrite any existing activity server to ensure using the
    		// same activity store as the situations provider
    		_callTraceService.setActivityServer(new ActivityServerAdapter()); 
    	}

    	if (_situations == null) {
	    	_acmManager = ActiveCollectionManagerAccessor.getActiveCollectionManager();
	
	    	_acmManager.addActiveCollectionListener(new ActiveCollectionListener() {
	
				@Override
				public void registered(ActiveCollection ac) {
					if (ac.getName().equals(SITUATIONS)) {
						synchronized (SITUATIONS) {
							if (_situations == null) {
						    	_situations = (ActiveList)ac;
						    	_situations.addActiveChangeListener(RTGovSituationsProvider.this);		
							}
						}
					}
				}
	
				@Override
				public void unregistered(ActiveCollection ac) {
				}
	    		
	    	});
    	}
    	
    	// TEMPORARY WORKAROUND: Currently hen active collection listener is registered, existing
    	// collections are not notified to the listener, thus potentially causing a situation where
    	// a collection may be missed if registered prior to the listener being established (RTGOV-286).
		synchronized (SITUATIONS) {
			if (_situations == null) {
		    	_situations = (ActiveList)_acmManager.getActiveCollection(SITUATIONS);
			}
			
	    	if (_situations != null) {
	    		_situations.addActiveChangeListener(RTGovSituationsProvider.this);	
	    	}
		}
    }

    /**
     * {@inheritDoc}
     */
	public String getName() {
		return PROVIDER_NAME;
	}

	/**
	 * {@inheritDoc}
	 */
	public void addSituationEventListener(SituationEventListener l) {
		synchronized (_listeners) {
			_listeners.add(l);
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void removeSituationEventListener(SituationEventListener l) {
		synchronized (_listeners) {
			_listeners.remove(l);
		}		
	}
	
	/**
	 * This method fires the situation event to any registered listeners.
	 * 
	 * @param event The situation event
	 */
	protected void fireSituationEvent(SituationEventBean event) {
		synchronized (_listeners) {
			for (int i=0; i < _listeners.size(); i++) {
				_listeners.get(i).onSituationEvent(event);
			}
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
    public java.util.List<SituationSummaryBean> search(SituationsFilterBean filters) throws UiException {
        ArrayList<SituationSummaryBean> situations = new ArrayList<SituationSummaryBean>();

        try {
	    	java.util.List<Situation> results=querySituations(filters);
	
	    	for (Situation item : results) {
	    	    // Check if only root nodes, and if so filter out situations that
	    	    // have been resubmitted
	    	    if (!filters.isRootOnly()
	    	            || !item.getProperties().containsKey(Situation.RESUBMITTED_SITUATION_ID)) {
	    	        SituationSummaryBean ssb=RTGovSituationsUtil.getSituationBean(item);
	    	        
	    	        // Identify resubmission failures
	    	        java.util.List<Situation> resubmitted=getResubmittedSituations(item.getId(), true);
	    	        ssb.setResubmissionFailureTotalCount(resubmitted.size());
                    
                    situations.add(ssb);	    	        
	    	    }
	        }
        } catch (Exception e) {
        	throw new UiException(e);
        }

        return (situations);
    }
    
    /**
     * This method creates a query from the supplied filter.
     * 
     * @param filter The filter
     * @return The query
     */
    protected static SituationsQuery createQuery(SituationsFilterBean filters) {
    	SituationsQuery ret=new SituationsQuery();

    	ret.setType(filters.getType());
    	
    	if (filters.getSeverity() != null && filters.getSeverity().trim().length() > 0) {
    		String severityName=Character.toUpperCase(filters.getSeverity().charAt(0))
    				+filters.getSeverity().substring(1);
    		ret.setSeverity(Situation.Severity.valueOf(severityName));
    	}
    	
    	if (filters.getTimestampFrom() != null) {
    		ret.setFromTimestamp(filters.getTimestampFrom().getTime());
    	}
    	
    	if (filters.getTimestampTo() != null) {
    		ret.setToTimestamp(filters.getTimestampTo().getTime());
    	}
    	ret.setDescription(filters.getDescription());
        ret.setResolutionState(filters.getResolutionState());
        ret.setSubject(filters.getSubject());
        try {
            ret.setProperties(filters.getProperties());
        } catch (IOException ioException) {
            Throwables.propagate(ioException);
        }

    	return (ret);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SituationBean getSituation(String situationId) throws UiException {
    	SituationBean ret=null;

    	try {
	    	Situation situation=_situationStore.getSituation(situationId);

	    	if (situation == null) {
	            throw new UiException(i18n.format("RTGovSituationsProvider.SitNotFound", situationId)); //$NON-NLS-1$
	    	}

	    	ret = RTGovSituationsUtil.getSituationBean(situation);
	    	
	    	MessageBean message = getMessage(situation);
	    	ret.setMessage(message);

	        CallTraceBean callTrace = getCallTrace(situation);
	        ret.setCallTrace(callTrace);
	        
            // Check if other situations have been created based on the
            // resubmission of this situation's message
            java.util.List<Situation> resubmits=getResubmittedSituations(situationId, true);
            ret.setResubmissionFailureTotalCount(resubmits.size());
            
            // If resubmit situations exist OR the situation is RESOLVED,
            // then resubmit of this situation is not possible
            if (resubmits.size() == 0
                    && !ret.getResolutionState().equalsIgnoreCase(ResolutionState.RESOLVED.name())) {
                ret.setResubmitPossible(any(_providers, new IsResubmitSupported(situation)));
            }
            
            ret.setManualResolutionPossible(RTGovProperties.getPropertyAsBoolean("UI.manualResolution",
                                    Boolean.TRUE));

    	} catch (UiException uie) {
    		throw uie;

    	} catch (Exception e) {
    		throw new UiException("Failed to retrieve situation", e); //$NON-NLS-1$
    	}

    	return (ret);
    }
    
    /**
     * This method returns the resubmitted situations.
     * 
     * @param situationId The parent situation id
     * @param deep Whether to traverse the tree (true) or just return the immediate child situations (false)
     * @return The list of resubmitted situations
     */
    protected java.util.List<Situation> getResubmittedSituations(String situationId, boolean deep) {
        java.util.List<Situation> results=new java.util.ArrayList<Situation>();
        
        queryResubmittedSituations(situationId, deep, results);
        
        return (results);
    }
    
    /**
     * This method queries the situation store to obtain situations resubmitted
     * by the situation associated with the supplied id.
     * 
     * @param situationId The parent situation id
     * @param deep Whether this should be done recursively
     * @param results The list of situations
     */
    protected void queryResubmittedSituations(String situationId, boolean deep,
                                        java.util.List<Situation> results) {
        SituationsQuery query=new SituationsQuery();
        query.getProperties().put(Situation.RESUBMITTED_SITUATION_ID, situationId);
        
        java.util.List<Situation> resubmitted=_situationStore.getSituations(query);
        
        for (Situation sit : resubmitted) {
            
            // Need to double check id, as fuzzy 'like' used when retrieving situations based on
            // properties to enable partial strings to be provided.
            if (!sit.getSituationProperties().containsKey(Situation.RESUBMITTED_SITUATION_ID)
                    || !sit.getSituationProperties().get(Situation.RESUBMITTED_SITUATION_ID)
                                .equals(situationId)) {
                continue;
            }
            
            if (!results.contains(sit)) {
                results.add(sit);

                if (deep) {
                    queryResubmittedSituations(sit.getId(), deep, results);
                }
            }
        }
    }

    /**
     * This method checks whether a request message exists for the supplied
     * situation and if so, returns a MessageBean to represent it's content.
     * 
     * @param situation The situation
     * @return The message, or null if not found
     * @throws UiException Failed to get message
     */
    protected MessageBean getMessage(Situation situation) throws UiException {
    	MessageBean ret=null;
    	
		for (ActivityTypeId id : situation.getActivityTypeIds()) {
			try {
		        ActivityType at=null;
		        
		        ActivityUnit au=_activityStore.getActivityUnit(id.getUnitId());
		        
		        if (au != null && id.getUnitIndex() < au.getActivityTypes().size()) {
		        	at = au.getActivityTypes().get(id.getUnitIndex());
		        }
    			
    			if (at instanceof RPCActivityType && ((RPCActivityType)at).isRequest()
    					&& ((RPCActivityType)at).getContent() != null) {
    				ret = new MessageBean();
    				ret.setContent(((RPCActivityType)at).getContent());
    				
    				// Handle header properties that need to be copied over
    				configureHeaders(ret, at);
    				
    				// Associate principal with the message
    				ret.setPrincipal(((RPCActivityType)at).getPrincipal());
    				
    				if (ret.getPrincipal() == null && au.getOrigin() != null) {
    				    ret.setPrincipal(au.getOrigin().getPrincipal());
    				}

    				break;
    			}
			} catch (Exception e) {
	    		throw new UiException("Failed to get message for activity type id '"+id+"'", e);
			}
		}
		
    	return (ret);
    }
    
    /**
     * This method copies the header properties from the activity type into the message
     * bean.
     * 
     * @param mb Messag bean
     * @param at The activity type
     */
    protected static void configureHeaders(MessageBean mb, ActivityType at) throws UiException {
        if (at != null) {
            for (String key : at.getProperties().keySet()) {
                if (isHeaderFormatProperty(key)) {
                    String format=at.getProperties().get(key);
                    String propName=getPropertyName(key);
                    
                    if (format != null) {
                        mb.getHeaders().put(propName, at.getProperties().get(propName));
                        mb.getHeaderFormats().put(propName, format);
                    }
                }
            }
        }
    }
    
    /**
     * This method determines whether a property name represents a header property.
     * 
     * @param headerName The property name
     * @return Whether a header property
     */
    protected static boolean isHeaderFormatProperty(String headerName) {
        return (headerName.startsWith(ActivityType.HEADER_FORMAT_PROPERTY_PREFIX));
    }
    
    /**
     * This method returns the property name associated with the header.
     * 
     * @param headerName The property name
     * @return The property name associated with the header name
     */
    protected static String getPropertyName(String headerName) {
        return (headerName.substring(ActivityType.HEADER_FORMAT_PROPERTY_PREFIX.length()));
    }
    
    /**
     * This method retrieves the call trace for the supplied situation.
     *
     * @param situation The situation
     * @return The call trace
     */
    protected CallTraceBean getCallTrace(Situation situation) throws UiException {
        CallTraceBean ret = new CallTraceBean();
        
        // Obtain call trace
        Context context=null;
        
        for (Context c : situation.getContext()) {
        	if (c.getType() == Context.Type.Conversation) {
        		context = c;
        		break;
        	}
        }
        
        if (context == null && situation.getContext().size() > 0) {
        	// If no conversation context available, then use any other
        	context = situation.getContext().iterator().next();
        }
        
        if (context != null && _callTraceService != null) {
        	try {
        		CallTrace ct=_callTraceService.createCallTrace(context);
        		
        		if (ct != null) {
        			for (TraceNode tn : ct.getTasks()) {
        				ret.getTasks().add(createTraceNode(tn));
        			}
        			
        		}
        	} catch (Exception e) {
        		throw new UiException("Failed to get call trace for context '"+context+"'", e);
        	}
        }

        return (ret);
    }
    
    /**
     * This method creates a UI bean from the supplied trace node.
     * 
     * @param node The trace node
     * @return The trace node bean
     */
    protected TraceNodeBean createTraceNode(TraceNode node) {
    	TraceNodeBean ret=new TraceNodeBean();
    	
    	ret.setType(node.getClass().getSimpleName());
    	ret.setStatus(node.getStatus().name());
    	
    	if (node instanceof Task) {
    		Task task=(Task)node;
    		
    		ret.setDescription(task.getDescription());
    		
    	} else if (node instanceof Call) {
    		Call call=(Call)node;
    		
        	ret.setIface(call.getInterface());
            ret.setOperation(call.getOperation());
            ret.setDuration(call.getDuration());
            ret.setPercentage(call.getPercentage());
            ret.setComponent(call.getComponent());
            ret.setFault(call.getFault());
            ret.setPrincipal(call.getPrincipal());
            ret.setRequest(call.getRequest());
            ret.setResponse(call.getResponse());
            ret.setRequestLatency(call.getRequestLatency());
            ret.setResponseLatency(call.getResponseLatency());
            
            ret.setProperties(call.getProperties());
        	
        	for (TraceNode child : call.getTasks()) {
				ret.getTasks().add(createTraceNode(child));
        	}
    	}
    	
    	return (ret);
    }
    
    /**
     * @see org.overlord.rtgov.ui.server.services.ISituationsServiceImpl#resubmit(java.lang.String, java.lang.String)
     */
    @Override
    public void resubmit(String situationId, MessageBean message, String username) throws UiException {
        Situation situation=_situationStore.getSituation(situationId);
        if (situation == null) {
            throw new UiException(i18n.format("RTGovSituationsProvider.SitNotFound", situationId)); //$NON-NLS-1$
        }
        
        resubmitInternal(situation, message, username);
    }

    private void resubmitInternal(Situation situation, MessageBean message, String username) throws UiException {
        final ServiceOperationName operationName = getServiceOperationName(situation);
        Optional<ServicesProvider> serviceProvider = tryFind(_providers, new IsResubmitSupported(
                operationName));
        if (!serviceProvider.isPresent()) {
            throw new UiException(i18n.format("RTGovSituationsProvider.ResubmitProviderNotFound", situation.getId())); //$NON-NLS-1$
        }
        
        // RTGOV-649 If situation resolved, then should not allow resubmit
        if (situation.getSituationProperties().containsKey(SituationStore.RESOLUTION_STATE_PROPERTY)
                && situation.getSituationProperties().get(SituationStore.RESOLUTION_STATE_PROPERTY)
                .equalsIgnoreCase(ResolutionState.RESOLVED.name())) {
            return;
        }
        
        // RTGOV-649 Assign situation to current user
        if (!situation.getSituationProperties().containsKey(SituationStore.ASSIGNED_TO_PROPERTY)
                || !situation.getSituationProperties().get(SituationStore.ASSIGNED_TO_PROPERTY).equals(username)) {
            assign(situation.getId(), username);
        }
        
        // RTGOV-649 Set resolution state to RESOLVED on the assumption that the resubmit will
        // fix the problem. If an immediate exception is received, then set it back to IN_PROGRESS,
        // and if a subsequent situation is reported, then it can also be set back to IN_PROGRESS.
        updateResolutionState(situation.getId(), ResolutionState.RESOLVED);

        // RTGOV-645 - include situation id, assignTo and resolutionState in resubmission, in case
        // further failures (resulting in linked situations) occur.
        message.getHeaders().put(Situation.RESUBMITTED_SITUATION_ID, situation.getId());
        
        try {
            ResubmitActionProvider resubmit=serviceProvider.get().getAction(ResubmitActionProvider.class);
            
            if (resubmit == null) {
                _situationStore.recordResubmitFailure(situation.getId(),
                        i18n.format("RTGovSituationsProvider.ResubmitNotSupported"), username);
            } else {
                resubmit.resubmit(operationName.getService(), operationName.getOperation(), message);
            
                _situationStore.recordSuccessfulResubmit(situation.getId(), username);
            }
        } catch (Exception exception) {
            // RTGOV-649 Set resolution state back to IN_PROGRESS
            updateResolutionState(situation.getId(), ResolutionState.IN_PROGRESS);

            _situationStore.recordResubmitFailure(situation.getId(),
                    Throwables.getStackTraceAsString(exception), username);
            throw new UiException(
                    i18n.format(
                            "RTGovSituationsProvider.ResubmitFailed", situation.getId() + ":" + exception.getLocalizedMessage()), exception); //$NON-NLS-1$
        }
    }
    
    @Override
    public BatchRetryResult resubmit(SituationsFilterBean situationsFilterBean, String username) throws UiException {
        int processedCount = 0, failedCount = 0, ignoredCount = 0;
        List<Situation> situationIdToactivityTypeIds = querySituations(situationsFilterBean);
        for (Situation situation : situationIdToactivityTypeIds) {
            
            // RTGOV-649 If situation resolved, then should not allow resubmit
            if (situation.getSituationProperties().containsKey(SituationStore.RESOLUTION_STATE_PROPERTY)
                    && situation.getSituationProperties().get(SituationStore.RESOLUTION_STATE_PROPERTY)
                    .equalsIgnoreCase(ResolutionState.RESOLVED.name())) {
                continue;
            }

            // Check if situation is root, and has resubmission failures
            if (situationsFilterBean.isRootOnly()) {
                java.util.List<Situation> resubmits=getResubmittedSituations(situation.getId(), true);
                
                if (resubmits.size() > 0) {
                    situation = resubmits.get(resubmits.size()-1);
                }
            }
            
            MessageBean message = getMessage(situation);
            if (message == null) {
                ignoredCount++;
                continue;
            }
            try {
                processedCount++;
                resubmitInternal(situation, message, username);
            } catch (UiException uiException) {
                failedCount++;
            }
        }
        return new BatchRetryResult(processedCount, failedCount, ignoredCount);
    }
    
    
    @Override
    public java.util.List<SituationSummaryBean> getResubmitFailures(String situationId) throws UiException {
        ArrayList<SituationSummaryBean> situations = new ArrayList<SituationSummaryBean>();

        try {
            java.util.List<Situation> results=getResubmittedSituations(situationId, true);
    
            for (Situation item : results) {
                SituationSummaryBean ssb=RTGovSituationsUtil.getSituationBean(item);
                
                situations.add(ssb);                    
            }
        } catch (Exception e) {
            throw new UiException(e);
        }

        return (situations);
    }
    
    @Override
    public void export(SituationsFilterBean situationsFilterBean, OutputStream outputStream) {
        List<Situation> situations = querySituations(situationsFilterBean);
        PrintWriter printWriter = new PrintWriter(outputStream);
        try {
            for (Situation situation : situations) {
                // Check if situation is root, and has resubmission failures
                if (situationsFilterBean.isRootOnly()) {
                    java.util.List<Situation> resubmits=getResubmittedSituations(situation.getId(), true);
                    
                    if (resubmits.size() > 0) {
                        situation = resubmits.get(resubmits.size()-1);
                    }
                }
                
                MessageBean message = getMessage(situation);
                if (message == null) {
                    continue;
                }
                printWriter.println(message.getContent());

            }
        } catch (UiException uiException) {
            Throwables.propagate(uiException);
        } finally {
            if (null != printWriter) {
                printWriter.close();
            }
        }

    }
    
    
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void inserted(Object key, Object value) {
		if (value instanceof Situation) {
			SituationEventBean event=RTGovSituationsUtil.getSituationEventBean((Situation)value);
			
			fireSituationEvent(event);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void updated(Object key, Object value) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void removed(Object key, Object value) {
	}

	/**
	 * This method returns the root situation associated with
	 * a resubmit hierarchy (i.e. where one situation is created
	 * as the result of a resubmission failure from a previous
	 * situation).
	 * 
	 * @param situationId The situation id
	 * @return The root situation, or null if this situation does
	 *        not have a resubmitted situation
	 */
	protected Situation getRootSituation(String situationId) throws UiException {
	    return (getRootSituation(_situationStore.getSituation(situationId)));
	}
	
    /**
     * This method returns the root situation associated with
     * a resubmit hierarchy (i.e. where one situation is created
     * as the result of a resubmission failure from a previous
     * situation).
     * 
     * @param original The original situation
     * @return The root situation, or null if this situation does
     *        not have a resubmitted situation
     */
    protected Situation getRootSituation(Situation original) throws UiException {
	    Situation root=original;
	    
	    if (original != null) {
	        String parentId=original.getProperties().get(Situation.RESUBMITTED_SITUATION_ID);
	        
	        while (parentId != null) {
	            root = _situationStore.getSituation(parentId);
	            
	            if (root == null) {
	                // Failed to retrieve parent situation
	                throw new UiException("Failed to locate parent situation");
	            }
	            
	            parentId = root.getProperties().get(Situation.RESUBMITTED_SITUATION_ID);
	        }
	    }
	    
	    return (root);
	}
	
	@Override
	public void assign(String situationId, final String userName) throws UiException {
        SituationsAction action=new SituationsAction() {
            @Override
            public void perform(Situation situation) throws Exception {
                _situationStore.assignSituation(situation.getId(), userName);
            }
        };
        
        Situation root=getRootSituation(situationId);
        
        if (root != null) {        
            try {
                performAction(root, action);
            } catch (UiException uie) {
                throw uie;
            } catch (Exception e) {
                throw new UiException(e);
            }
        }
	}

	@Override
	public void unassign(String situationId) throws UiException {
        SituationsAction action=new SituationsAction() {
            @Override
            public void perform(Situation situation) throws Exception {
                _situationStore.unassignSituation(situation.getId());
            }
        };
        
        Situation root=getRootSituation(situationId);
        
        if (root != null) {
            try {
                performAction(root, action);
            } catch (UiException uie) {
                throw uie;
            } catch (Exception e) {
                throw new UiException(e);
            }
        }
	}

	@Override
	public void updateResolutionState(String situationId, final ResolutionState resolutionState) throws UiException {
	    SituationsAction action=new SituationsAction() {
            @Override
            public void perform(Situation situation) throws Exception {
                _situationStore.updateResolutionState(situation.getId(),
                        org.overlord.rtgov.analytics.situation.store.ResolutionState.valueOf(resolutionState.name()));
            }
	    };
	    
        Situation root=getRootSituation(situationId);
        
        if (root != null) {
            try {
                performAction(root, action);
            } catch (UiException uie) {
                throw uie;
            } catch (Exception e) {
                throw new UiException(e);
            }
        }
	}

	protected void performAction(Situation root, SituationsAction action) throws Exception {
	    action.perform(root);
	    
	    // Check if situation has child situations
	    java.util.List<Situation> resubmitted=getResubmittedSituations(root.getId(), true);
	    
	    for (Situation sit : resubmitted) {
	        action.perform(sit);
	    }
	}
	
	protected java.util.List<Situation> querySituations(SituationsFilterBean situationsFilterBean) {	    
        SituationsQuery query=createQuery(situationsFilterBean);
        
        java.util.List<Situation> results=_situationStore.getSituations(query);
        
        // Check if only root situations should be returned
        if (situationsFilterBean.isRootOnly()) {
            for (int i=results.size()-1; i >= 0; i--) {
                if (results.get(i).getProperties().containsKey(Situation.RESUBMITTED_SITUATION_ID)) {
                    results.remove(i);
                }
            }
        }
        
        return (results);
	}
	
    @Override
    public int delete(SituationsFilterBean situationsFilterBean) throws UiException {
        try {
            final java.util.List<Situation> results=querySituations(situationsFilterBean);
            final java.util.Set<Situation> deletions=new java.util.HashSet<Situation>();

            for (Situation sit : results) {
                Situation root=sit;
                
                if (!situationsFilterBean.isRootOnly()) {
                    root = getRootSituation(sit);
                }
                
                deletions.add(root);
                
                java.util.List<Situation> resubmitted=getResubmittedSituations(root.getId(), true);
                
                deletions.addAll(resubmitted);
            }
            
            int count=0;
            
            for (Situation sit : deletions) {
                _situationStore.delete(sit);
                count++;
            }

            return (count);
        } catch (Exception e) {
            throw new UiException(e);
        }
    }

    /**
     * This class provides a simple activity server adapter that passes requests
     * through to the activity store.
     *
     */
    protected class ActivityServerAdapter implements ActivityServer {

    	/**
    	 * {@inheritDoc}
    	 */
		@Override
		public void store(List<ActivityUnit> activities) throws Exception {
		}

    	/**
    	 * {@inheritDoc}
    	 */
		@Override
		public ActivityUnit getActivityUnit(String id) throws Exception {
			return (_activityStore.getActivityUnit(id));
		}

    	/**
    	 * {@inheritDoc}
    	 */
		@Override
		public List<ActivityType> getActivityTypes(Context context)
				throws Exception {
			return (_activityStore.getActivityTypes(context));
		}

    	/**
    	 * {@inheritDoc}
    	 */
		@Override
		public List<ActivityType> getActivityTypes(Context context, long from,
				long to) throws Exception {
			return (_activityStore.getActivityTypes(context, from, to));
		}

    	/**
    	 * {@inheritDoc}
    	 */
		@Override
		public List<ActivityType> query(QuerySpec query) throws Exception {
			return (_activityStore.query(query));
		}
    	
    }
    
    private static ServiceOperationName getServiceOperationName(Situation situation) throws UiException {
        if (situation == null) {
            throw new IllegalArgumentException("parameter 'situation' must not be null");
        }
        String parts[] = Strings.nullToEmpty(situation.getSubject()).split("\\x7C");
        if (parts.length < 2 || parts.length > 3) {
            throw new UiException(i18n.format("RTGovSituationsProvider.InvalidSubject",
                    situation.getSubject(), parts.length));
        }
        return new ServiceOperationName(parts[0], parts[1]);
    }
    
    /**
     * Predicate to test
     * {@link ServicesProvider#isResubmitSupported(String, String)}
     */
    private final class IsResubmitSupported implements com.google.common.base.Predicate<ServicesProvider> {
        private final ServiceOperationName operationName;

        private IsResubmitSupported(Situation situation) throws UiException {
            this(getServiceOperationName(situation));
        }

        private IsResubmitSupported(ServiceOperationName operationName) {
            this.operationName = operationName;
        }

		@Override
		public boolean apply(ServicesProvider input) {
			try {
			    ResubmitActionProvider resubmit=input.getAction(ResubmitActionProvider.class);
			    
			    if (resubmit == null) {
			        return false;
			    }
			    
				return resubmit.isResubmitSupported(operationName.getService(), operationName.getOperation());
			} catch (UiException e) {
				Throwables.propagate(e);
			}
			return false;
		}
    }
    
    /**
     * Simple value object for service and operation name.
     * 
     */
    private static class ServiceOperationName {
        private String service;
        private String operation;

        private ServiceOperationName(String service, String operation) {
            super();
            this.service = service;
            this.operation = operation;
        }

        /**
         * @return the service
         */
        public String getService() {
            return service;
        }

        /**
         * @return the operation
         */
        public String getOperation() {
            return operation;
        }

    }
    
    /**
     * Interface for performing an action on a situation.
     *
     */
    public interface SituationsAction {

        /**
         * This method performs the action on a specified situation.
         * 
         * @param situation The situation
         * @throws Exception Failed to perform action
         */
        public void perform(Situation situation) throws Exception;
    }
}
