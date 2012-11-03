package com.salaboy.patterns;

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.resource.jdbc.PoolingDataSource;
import com.salaboy.model.Person;
import com.salaboy.patterns.handler.MockAsyncExternalServiceWorkItemHandler;
import com.salaboy.sessions.patterns.BusinessEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.naming.InitialContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.transaction.UserTransaction;
import junit.framework.Assert;
import org.drools.KnowledgeBase;
import org.drools.KnowledgeBaseFactory;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderError;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.impl.EnvironmentFactory;
import org.drools.io.Resource;
import org.drools.io.ResourceFactory;
import org.drools.persistence.jpa.*;
import org.drools.runtime.Environment;
import org.drools.runtime.EnvironmentName;
import org.drools.runtime.StatefulKnowledgeSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Group of tests that show how a session can be used to hold a single or multiple
 * process instances.
 * @author salaboy
 */
public class SingleSessionPatternsTest {

    private PoolingDataSource ds = new PoolingDataSource();
    private Map<String, KnowledgeBase> kbases;
    private EntityManagerFactory emf;

    public SingleSessionPatternsTest() {
    }

    /**
     * Configure the data source used to persist the sessions used in these
     * tests.
     */
    @Before
    public void setUp() {
        ds.setUniqueName("jdbc/testDS1");

        ds.setClassName("org.h2.jdbcx.JdbcDataSource");
        ds.setMaxPoolSize(3);
        ds.setAllowLocalTransactions(true);
        ds.getDriverProperties().put("user", "sa");
        ds.getDriverProperties().put("password", "sasa");
        ds.getDriverProperties().put("URL", "jdbc:h2:mem:mydb");

        ds.init();


        emf = Persistence.createEntityManagerFactory("org.jbpm.runtime");
        kbases = new HashMap<String, KnowledgeBase>();
    }

    @After
    public void tearDown() {
        ds.close();
    }

    /**
     * This test starts 2 process instances of the same process definition
     * in independent sessions.
     * @throws Exception 
     */
    @Test
    public void singleSessionPerProcessInstance() throws Exception {
        //Creates an entity manager and get the user transaction. We are going
        //to need them later to interact with the business entities persisted
        //by the work item handlers we have configured in our session.
        EntityManager em = emf.createEntityManager();
        UserTransaction ut = (UserTransaction) new InitialContext().lookup("java:comp/UserTransaction");

        //Initial parameters for process instance #1
        Person person = new Person("Salaboy", 29);
        Map<String, Object> params1 = new HashMap<String, Object>();
        params1.put("person", person);

        //Creates the ksession for process instance #1
        StatefulKnowledgeSession ksession1 = createProcessOneKnowledgeSession(person.getId());
        registerWorkItemHandlers(ksession1, person.getId(), em);
        int ksession1Id = ksession1.getId();

        //Starts process instance #1
        ksession1.startProcess("com.salaboy.process.AsyncInteractions", params1);

        //We don't want to use the ksession anymore so we will dispose it.
        //At this point MockAsyncExternalServiceWorkItemHandler has persisted
        //a business key that we can use later to retireve the session from
        //the database and continue with the execution of the process.
        ksession1.dispose();



        //Initial parameters for process instance #2
        Person person2 = new Person("Salaboy2", 29);
        Map<String, Object> params2 = new HashMap<String, Object>();
        params2.put("person", person2);

        //Creates a new ksession for process instance #2
        StatefulKnowledgeSession ksession2 = createProcessTwoKnowledgeSession(person2.getId());
        registerWorkItemHandlers(ksession2, person2.getId(), em);
        int ksession2Id = ksession2.getId();

        //Starts process instance #2
        ksession2.startProcess("com.salaboy.process.AsyncInteractions", params2);

        //Dispose ksession2 as we don't want to use it anymore. Just like with
        //process instance #1, the work item handler associated to the task nodes
        //of the process has persisted a business key that we can use to continue
        //with the execution of this session later.
        ksession2.dispose();




        //Let's find the BusinessEntity persisted by process instance #2.
        //The key of the BusinessEntity is the the persnon's id.
        BusinessEntity businessEntity = getBusinessEntity(person2.getId(), em);
        assertNotNull(businessEntity);
        //the BusinessEntity must be of session #2
        assertEquals(businessEntity.getSessionId(), ksession2Id);


        //We shouldn't have more active business entities in the database.
        List<BusinessEntity> activeBusinessEntities = getActiveBusinessEntities(em);
        assertTrue(activeBusinessEntities.size() == 2);

        //Let' restore the session #2 using the information present in the BusinessEntity
        //Since we keep one kbase per ksession we also need to get it using
        //the information present in the BusinessEntity.
        ksession2 = JPAKnowledgeService.loadStatefulKnowledgeSession(businessEntity.getSessionId(), kbases.get(businessEntity.getBusinessKey()), null, createEnvironment());
        registerWorkItemHandlers(ksession2, businessEntity.getBusinessKey(), em);
        assertNotNull(ksession2);

        try {
            ut.begin();
            //Now that we have session #2 back we can complete the pending work item 
            //handler with the information present in BusinessEntity we can 
            ksession2.getWorkItemManager().completeWorkItem(businessEntity.getWorkItemId(), null);
            //The BusinessEntity is no longer needed so we can marked as completed
            //in the database.
            markBusinessEntityAsCompleted(businessEntity.getId(), em);
            ut.commit();
        } catch (Exception e) {
            System.out.println("Rolling back because of: " + e.getMessage());
            ut.rollback();
        }

        //We are done with ksession #2
        ksession2.dispose();




        //Now we are going to complete the pending work item handler of 
        //the process instance #1, but first we need to restore the session from
        //the database.
        businessEntity = getBusinessEntity(person.getId(), em);
        assertNotNull(businessEntity);

        //the BusinessEntity must be of session #1
        assertEquals(businessEntity.getSessionId(), ksession1Id);

        //load the ksession using the information present in BusinessEntity
        ksession1 = JPAKnowledgeService.loadStatefulKnowledgeSession(businessEntity.getSessionId(), kbases.get(businessEntity.getBusinessKey()), null, createEnvironment());
        registerWorkItemHandlers(ksession1, businessEntity.getBusinessKey(), em);
        assertNotNull(ksession1);

        try {
            // This needs to happen in the same transaction in order to be consistent
            ut.begin();
            //complete the pending work item handler
            ksession1.getWorkItemManager().completeWorkItem(businessEntity.getWorkItemId(), null);
            //mark the BusinessEntity as completed
            markBusinessEntityAsCompleted(businessEntity.getId(), em);
            ut.commit();
        } catch (Exception e) {
            System.out.println("Rolling back because of: " + e.getMessage());
            ut.rollback();
        }


        //dispose ksession #1
        ksession1.dispose();

        //We should have two active business entities in the database. Because the processes have two workitems each.
        activeBusinessEntities = getActiveBusinessEntities(em);
        assertEquals(2, activeBusinessEntities.size());

        //We should have two inactive business entities in the database.
        List<BusinessEntity> inActiveBusinessEntities = getInactiveBusinessEntities(em);
        assertEquals(2, inActiveBusinessEntities.size());

    }

    /**
     * This test starts 2 process instances of the same process definition
     * in the same sessions.
     * @throws Exception 
     */
    @Test
    public void singleSessionPerProcessDefinition() throws Exception {
        //Creates an entity manager and get the user transaction. We are going
        //to need them later to interact with the business entities persisted
        //by the work item handlers we have configured in our session.
        EntityManager em = emf.createEntityManager();
        UserTransaction ut = (UserTransaction) new InitialContext().lookup("java:comp/UserTransaction");

        //Initial parameters for process instance #1
        Person person = new Person("Salaboy", 29);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("person", person);

        //Creates the ksession for process instance #1
        StatefulKnowledgeSession ksession1 = createProcessOneKnowledgeSession("myProcessDefinitionSession");
        registerWorkItemHandlers(ksession1, null, em);

        
        //Starts process instance #1
        ksession1.startProcess("com.salaboy.process.AsyncInteractions", params);

        //We don't want to use the ksession anymore so we will dispose it.
        //At this point MockAsyncExternalServiceWorkItemHandler has persisted
        //a business key that we can use later to retireve the session from
        //the database and continue with the execution of the process.
        ksession1.dispose();


        //Let's retrieve the business entity persisted by the work item handler
        //in order to get the session id where the process was running.
        //In this case, we could have just get the session id from ksession1
        //object, but we are emulating a real world situation here.
        List<BusinessEntity> activeBusinessEntities = getActiveBusinessEntities(em);
        Assert.assertEquals(1, activeBusinessEntities.size());
        int ksession1Id = activeBusinessEntities.get(0).getSessionId();


        
        
        
        //In this case we want to start the new process instance in the same 
        //ksession we used before. That is why we first need to retrieve it from
        //the database.
        ksession1 = JPAKnowledgeService.loadStatefulKnowledgeSession(ksession1Id, kbases.get("myProcessDefinitionSession"), null, createEnvironment());
        registerWorkItemHandlers(ksession1, null, em);
        assertNotNull(ksession1);

        //Let's prepare a new set of data to start a new process instance of 
        //the same process definition we used before.
        Person person2 = new Person("Salaboy", 29);
        Map<String, Object> params2 = new HashMap<String, Object>();
        params2.put("person", person2);
        
        //Starts process instance #1
        ksession1.startProcess("com.salaboy.process.AsyncInteractions", params2);

        //We are no longer interested in the session, so we can dispose it.
        ksession1.dispose();
        
        
        
        //Getting the correct work item to finish:
        //If we don't know which workItem do we want to complete we can create 
        //a query to see which are pending work items for a process or for a 
        //more complex business key.
        //If the thread that wants to notify the engine about the completion of 
        //the external interaction is the one which has created the token inside 
        //the WorkItemHandler it can use that unique value to get the related 
        //workItemId.
        BusinessEntity businessEntityByWorkItemId = getBusinessEntityByWorkItemId(1L, em);

        //Before completing the work item we need to reload the session once again.
        ksession1 = JPAKnowledgeService.loadStatefulKnowledgeSession(businessEntityByWorkItemId.getSessionId(), kbases.get("myProcessDefinitionSession"), null, createEnvironment());
        registerWorkItemHandlers(ksession1, null, em);
        assertNotNull(ksession1);

        try {
            // This needs to happen in the same transaction in order to be consistent
            ut.begin();
            //complete the pending work item handler
            ksession1.getWorkItemManager().completeWorkItem(businessEntityByWorkItemId.getWorkItemId(), null);
            //mark the BusinessEntity as completed
            markBusinessEntityAsCompleted(businessEntityByWorkItemId.getId(), em);
            ut.commit();
        } catch (Exception e) {
            System.out.println("Rolling back because of: " + e.getMessage());
            ut.rollback();
        }

        //disposes the session
        ksession1.dispose();

        //The only pending workItem related to the processId 2 should be 2
        //We can create queries to find out the pending workItems for a process 
        //instance or to find a process instance related to a business scenario 
        //using this approach.
        List<BusinessEntity> businessEntitiesByProcessId = getBusinessEntitiesProcessId(2L, em);
        assertEquals(1, businessEntitiesByProcessId.size());

        assertEquals(2, businessEntitiesByProcessId.get(0).getWorkItemId());

    }

    /**
     * This test uses a single session to start all the instances of a process 
     * definition. The singularity of the test is that it never starts a process
     * instance directly. Instead of that the session uses a rule to determine
     * when a process instance must be started.
     * @throws Exception 
     */
    @Test
    public void singleSessionPerProcessDefinitionWithRules() throws Exception{
        //Creates an entity manager and get the user transaction. We are going
        //to need them later to interact with the business entities persisted
        //by the work item handlers we have configured in our session.
        EntityManager em = emf.createEntityManager();
        UserTransaction ut = (UserTransaction) new InitialContext().lookup("java:comp/UserTransaction");
        
        //Creates the ksession. In this case the ksession
        //will not only contains the process definition but it is also going to
        //have a rule that is going to start a process instance for each
        //Person object we insert in the session.
        StatefulKnowledgeSession ksession = createProcessWithRulesKnowledgeSession("myProcessDefinitionSession");
        registerWorkItemHandlers(ksession, "myProcessDefinitionSession", em);

        //Instead of manually starting a new process instance we will let the
        //rules to start it when they consider it is necessary.
        //In this case we are going to instantiate a Person object and insert it
        //into the session. After this we will call ksession.fireAllRules() to
        //execute any activated rule.
        Person person = new Person("Salaboy", 29);
        ksession.insert(person);
        ksession.fireAllRules();

        //Dispose the session since we are no longer interested in it.
        ksession.dispose();

        
        //At this point, a process instance must be started and we should have
        //a business entity persisted in the database. Remember that this entity
        //was persisted by the work item hanlder we have configured for our tasks.
        BusinessEntity businessEntity = getBusinessEntity("myProcessDefinitionSession", em);
        Assert.assertNotNull(businessEntity);

        //Let's restore the session now to insert a new instance of Person and
        //see what happens. In order to restore the session we are using the
        //sessionId present in the business entity we have retrieved from the 
        //database.
        ksession = JPAKnowledgeService.loadStatefulKnowledgeSession(businessEntity.getSessionId(), kbases.get("myProcessDefinitionSession"), null, createEnvironment());
        registerWorkItemHandlers(ksession, "myProcessDefinitionSession", em);
        assertNotNull(ksession);

        //Let's create a new Person and insert it in the sesssion. This will
        //cause a new process instance to be launched.
        Person person2 = new Person("Salaboy", 29);
        ksession.insert(person2);
        ksession.fireAllRules();

        //Dispose the session since we are no longer interested in it.
        ksession.dispose();


        //Getting the correct work item to finish:
        //If we don't know which workItem do we want to complete we can create 
        //a query to see which are pending work items for a process or for a 
        //more complex business key.
        //If the thread that wants to notify the engine about the completion of 
        //the external interaction is the one which has created the token inside 
        //the WorkItemHandler it can use that unique value to get the related 
        //workItemId.
        BusinessEntity businessEntityByWorkItemId = getBusinessEntityByWorkItemId(1L, em);

        //Before completing the work item we need to reload the session once again.
        ksession = JPAKnowledgeService.loadStatefulKnowledgeSession(businessEntityByWorkItemId.getSessionId(), kbases.get("myProcessDefinitionSession"), null, createEnvironment());
        registerWorkItemHandlers(ksession, "myProcessDefinitionSession", em);
        assertNotNull(ksession);

        
         try {
            // This needs to happen in the same transaction in order to be consistent
            ut.begin();
            //complete the pending work item handler
            ksession.getWorkItemManager().completeWorkItem(businessEntityByWorkItemId.getWorkItemId(), null);
            //mark the BusinessEntity as completed
            markBusinessEntityAsCompleted(businessEntityByWorkItemId.getId(), em);
            ut.commit();
        } catch (Exception e) {
            System.out.println("Rolling back because of: " + e.getMessage());
            ut.rollback();
        }
        
        //Dispose the session since we are no longer interested in it.
        ksession.dispose();

        //The only pending workItem related to the processId 2 should be 2
        //We can create queries to find out the pending workItems for a process 
        //instance or to find a process instance related to a business scenario 
        //using this approach.
        List<BusinessEntity> businessEntitiesProcessId = getBusinessEntitiesProcessId(2L, em);
        assertEquals(1, businessEntitiesProcessId.size());
        assertEquals(2, businessEntitiesProcessId.get(0).getWorkItemId());
        assertEquals(2, businessEntitiesProcessId.get(0).getProcessId());


    }

    /**
     * Retrieves the {@link BusinessEntity} related to a workItemId from the 
     * database. 
     * @param workItemId the workItemId.
     * @param em the EntityManager to be used.
     * @return the {@link BusinessEntity} related to a workItemId from the 
     * database
     */
    private BusinessEntity getBusinessEntityByWorkItemId(long workItemId, EntityManager em) {
        return (BusinessEntity) em.createQuery("select be from BusinessEntity be where be.workItemId = :workItemId and be.active = true")
                .setParameter("workItemId", workItemId)
                .getSingleResult();
    }

    /**
     * Returns the list of all active BusinessEntities in the database.
     * @param em the EntityManager to be used.
     * @return the list of all active BusinessEntities in the database
     */
    private List<BusinessEntity> getActiveBusinessEntities(EntityManager em) {
        List<BusinessEntity> businessEntities = em.createQuery("select be from BusinessEntity be where be.active = true").getResultList();
        return businessEntities;
    }

    /**
     * Returns the list of all inactive BusinessEntities in the database.
     * @param em the EntityManager to be used.
     * @return the list of all inactive BusinessEntities in the database
     */
    private List<BusinessEntity> getInactiveBusinessEntities(EntityManager em) {
        List<BusinessEntity> businessEntities = em.createQuery("select be from BusinessEntity be where be.active = false").getResultList();
        return businessEntities;
    }

    /**
     * Queries the database to retrieve a {@link BusinessEntity} given its key.
     * Only active BusinessEntities are returned.
     * @param key the key of the BusinessEntity.
     * @param em the EntityManager to be used.
     * @return the BusinessEntity with the given key.
     */
    private BusinessEntity getBusinessEntity(String key, EntityManager em) {
        BusinessEntity businessEntity = (BusinessEntity) em.createQuery("select be from BusinessEntity be where be.businessKey = :key "
                + "and be.active = true")
                .setParameter("key", key)
                .getSingleResult();

        return businessEntity;
    }

    /**
     * Queries the database to retrieve all the {@link BusinessEntity} belonging
     * to a process instance. Only active BusinessEntities are returned.
     * @param processId the process id
     * @param em the EntityManager to be used.
     * @return the {@link BusinessEntity} belonging to a process instance
     */
    private List<BusinessEntity> getBusinessEntitiesProcessId(long processId, EntityManager em) {
        List<BusinessEntity> businessEntities = em.createQuery("select be from BusinessEntity be where  "
                + " be.processId = :processId"
                + " and be.active = true")
                .setParameter("processId", processId)
                .getResultList();
        return businessEntities;
    }

    /**
     * Sets the 'active' property of the businessEntity as 'false' and persists
     * it into the database.
     *
     * @param businessEntity
     */
    private void markBusinessEntityAsCompleted(Long businessEntityId, EntityManager em) {
        em.joinTransaction();
        BusinessEntity businessEntity = em.find(BusinessEntity.class, businessEntityId);
        businessEntity.setActive(false);
        System.out.println("Merging Business Entity: " + businessEntity);
        em.merge(businessEntity);
    }

    /**
     * Creates a new ksession containing a single process definition: 
     * 'process-async-interactions.bpmn'.
     * This method uses {@link #createKnowledgeSession(java.lang.String, java.util.Map)}
     * in order to create the ksession.
     * @param key The key used to register the kbase created with the resources
     * used by this method.
     * @return a new ksession containing a single process definition.
     */
    private StatefulKnowledgeSession createProcessOneKnowledgeSession(String key) {
        Map<Resource,ResourceType> resources = new HashMap<Resource, ResourceType>();
        resources.put(ResourceFactory.newClassPathResource("process-async-interactions.bpmn"), ResourceType.BPMN2);
        
        return this.createKnowledgeSession(key, resources);
    }

    /**
     * Creates a new ksession containing a single process definition: 
     * 'process-async-interactions2.bpmn'.
     * This method uses {@link #createKnowledgeSession(java.lang.String, java.util.Map)}
     * in order to create the ksession.
     * @param key The key used to register the kbase created with the resources
     * used by this method.
     * @return a new ksession containing a single process definition.
     */
    private StatefulKnowledgeSession createProcessTwoKnowledgeSession(String key) {
        Map<Resource,ResourceType> resources = new HashMap<Resource, ResourceType>();
        resources.put(ResourceFactory.newClassPathResource("process-async-interactions2.bpmn"), ResourceType.BPMN2);
        
        return this.createKnowledgeSession(key, resources);
    }

    /**
     * Creates a new ksession containing a single process definition: 
     * 'process-async-interactions.bpmn' and a rule definition: 'start-process-rules.drl'
     * This method uses {@link #createKnowledgeSession(java.lang.String, java.util.Map)}
     * in order to create the ksession.
     * @param key The key used to register the kbase created with the resources
     * used by this method.
     * @return a new ksession containing a single process definition and a rule.
     */
    private StatefulKnowledgeSession createProcessWithRulesKnowledgeSession(String key) {
        Map<Resource,ResourceType> resources = new HashMap<Resource, ResourceType>();
        resources.put(ResourceFactory.newClassPathResource("process-async-interactions.bpmn"), ResourceType.BPMN2);
        resources.put(ResourceFactory.newClassPathResource("start-process-rules.drl"), ResourceType.DRL);
        
        return this.createKnowledgeSession(key, resources);
    }
    
    /**
     * Creates a new Knowledge Base with the passed resources and returns a fresh
     * ksession from it. This method register the created kbase in {@link #kbases}
     * with the key passed as parameter. The returned session is configured as
     * persistent.
     * @param key the key used to register the generated kbase in {@link #kbases}.
     * @param resources The resources to be placed inside the generated kbase.
     * @return 
     */
    private StatefulKnowledgeSession createKnowledgeSession(String key, Map<Resource, ResourceType> resources) {
        
        //Creates a new kbuilder
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        
        //Adds al the given resources
        for (Map.Entry<Resource, ResourceType> entry : resources.entrySet()) {
            kbuilder.add(entry.getKey(), entry.getValue());
        }

        //If there is any compilation error then fail!
        if (kbuilder.hasErrors()) {
            for (KnowledgeBuilderError error : kbuilder.getErrors()) {
                System.out.println(">>> Error:" + error.getMessage());

            }
            fail(">>> Knowledge couldn't be parsed! ");
        }

        //Creates a new kbase
        KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
        
        //Add the generated knowledge packages from kbuilder.
        kbase.addKnowledgePackages(kbuilder.getKnowledgePackages());
        
        //Register the kbase in this.kbases
        kbases.put(key, kbase);

        //Creates a Persistence Knowledge Session
        System.out.println(" >>> Let's create a Persistent Knowledge Session");
        final StatefulKnowledgeSession ksession = JPAKnowledgeService.newStatefulKnowledgeSession(kbase, null, createEnvironment());

        return ksession;
    }
    
    /**
     * Register the Work Item handler we are going to use in our processes.
     * An instance of {@link MockAsyncExternalServiceWorkItemHandler} is used 
     * for all the 'Human Task' and 'External Service Call' tasks.
     * @param ksession the session where the handlers are registered.
     * @param key The business key used to instantiate {@link MockAsyncExternalServiceWorkItemHandler}
     * @param em The entity manager that the instance of {@link MockAsyncExternalServiceWorkItemHandler}
     * will use.
     */
    private void registerWorkItemHandlers(StatefulKnowledgeSession ksession, String key, EntityManager em) {
        MockAsyncExternalServiceWorkItemHandler mockExternalServiceWorkItemHandler = new MockAsyncExternalServiceWorkItemHandler(em, ksession.getId(), key);
        ksession.getWorkItemManager().registerWorkItemHandler("Human Task", mockExternalServiceWorkItemHandler);
        ksession.getWorkItemManager().registerWorkItemHandler("External Service Call", mockExternalServiceWorkItemHandler);
    }

    /**
     * Creates the persistence environment used by jBPM to handle persistent
     * sessions.
     * @return a new environment.
     */
    private Environment createEnvironment() {

        Environment env = EnvironmentFactory.newEnvironment();
        env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, emf);
        env.set(EnvironmentName.TRANSACTION_MANAGER, TransactionManagerServices.getTransactionManager());
        return env;
    }
}
