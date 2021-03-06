/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.cache.infinispan;

import org.jboss.logging.Logger;
import org.keycloak.migration.MigrationModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientTemplateModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.cache.infinispan.entities.CachedClient;
import org.keycloak.models.cache.infinispan.entities.CachedClientRole;
import org.keycloak.models.cache.infinispan.entities.CachedClientTemplate;
import org.keycloak.models.cache.infinispan.entities.CachedGroup;
import org.keycloak.models.cache.infinispan.entities.CachedRealm;
import org.keycloak.models.cache.infinispan.entities.CachedRealmRole;
import org.keycloak.models.cache.infinispan.entities.CachedRole;
import org.keycloak.models.cache.infinispan.entities.ClientListQuery;
import org.keycloak.models.cache.infinispan.entities.RealmListQuery;
import org.keycloak.models.cache.infinispan.entities.RoleListQuery;
import org.keycloak.models.utils.KeycloakModelUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * - the high level architecture of this cache is an invalidation cache.
 * - the cache is manual/custom versioned.  When a model is updated, we remove it from the cache
 * which causes an invalidation message to be sent across the cluster.
 * - We had to do it this way because Infinispan REPEATABLE_READ
 * wouldn't cut it in invalidation mode.  Also, REPEATABLE_READ doesn't work very well on relationships and items that are
 * not in the cache.
 * - There are two Infinispan caches.  One clustered that holds actual objects and a another local one that holds revision
 * numbers of cached objects.  Whenever a cached object is removed (invalidated), the local revision
 * cache number or that key is bumped higher based on a local version counter.  Whenever a cache entry is fetched, this
 * revision number is also fetched and compared against the revision number in the cache entry to see if the cache entry
 * is stale.  Whenever a cache entry is added, this revision number is also checked against the revision cache.
 * - Revision entries are actually never removed (although they could be evicted by cache eviction policies).  The reason for this
 * is that it is possible for a stale object to be inserted if one thread loads and the data is updated in the database before
 * it is added to the cache.  So, we keep the version number around for this.
 * - In a transaction, objects are registered to be invalidated.  If an object is marked for invalidation within a transaction
 * a cached object should never be returned.  An DB adapter should always be returned.
 * - At prepare phase of the transaction, a local lock on the revision cache will be obtained for each object marked for invalidation
 * we sort the list of these keys to order local acquisition and avoid deadlocks.
 * - After DB commits, the objects marked for invalidation are invalidated, or rather removed from the cache.  At this time
 * the revision cache entry for this object has its version number bumped.
 * - Whenever an object is marked for invalidation, the cache is also searched for any objects that are related to this object
 * and need to also be evicted/removed.  We use the Infinispan Stream SPI for this.
 *
 * ClientList caches:
 * - lists of clients are cached in a specific cache entry i.e. realm clients, find client by clientId
 * - realm client lists need to be invalidated and evited whenever a client is added or removed from a realm.  RealmProvider
 * now has addClient/removeClient at its top level.  All adapaters should use these methods so that the appropriate invalidations
 * can be registered.
 * - whenever a client is added/removed the realm of the client is added to a listInvalidations set
 * this set must be checked before sending back or caching a cached query.  This check is required to
 * avoid caching an uncommitted removal/add in a query cache.
 * - when a client is removed, any queries that contain that client must also be removed.
 * - a client removal will also cause anything that is contained and cached within that client to be removed
 *
 * Clustered caches:
 * - There is a Infinispan @Listener registered.  If an invalidation event happens, this is treated like
 * the object was removed from the database and will perform evictions based on that assumption.
 * - Eviction events will also cascade other evictions, but not assume this is a db removal.
 *
 * Groups and Roles:
 * - roles are tricky because of composites.  Composite lists are cached too.  So, when a role is removed
 * we also iterate and invalidate any role or group that contains that role being removed.
 *
 * - Clustering gotchyas. With an invalidation cache, if you remove an entry on node 1 and this entry does not exist on node 2, node 2 will not receive a @Listener invalidation event.
 * so, hat we have to put a marker entry in the invalidation cache before we read from the DB, so if the DB changes in between reading and adding a cache entry, the cache will be notified and bump
 * the version information.
 *
 * - any relationship should be resolved from session.realms().  For example if JPA.getClientByClientId() is invoked,
 *  JPA should find the id of the client and then call session.realms().getClientById().  THis is to ensure that the cached
 *  object is invoked and all proper invalidation are being invoked.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class StreamCacheRealmProvider implements CacheRealmProvider {
    protected static final Logger logger = Logger.getLogger(StreamCacheRealmProvider.class);
    public static final String REALM_CLIENTS_QUERY_SUFFIX = ".realm.clients";
    public static final String ROLES_QUERY_SUFFIX = ".roles";
    public static final String ROLE_BY_NAME_QUERY_SUFFIX = ".role.by-name";
    protected StreamRealmCache cache;
    protected KeycloakSession session;
    protected RealmProvider delegate;
    protected boolean transactionActive;
    protected boolean setRollbackOnly;

    protected Map<String, RealmModel> managedRealms = new HashMap<>();
    protected Map<String, ClientModel> managedApplications = new HashMap<>();
    protected Map<String, ClientTemplateModel> managedClientTemplates = new HashMap<>();
    protected Map<String, RoleModel> managedRoles = new HashMap<>();
    protected Map<String, GroupModel> managedGroups = new HashMap<>();
    protected Set<String> listInvalidations = new HashSet<>();
    protected Set<String> invalidations = new HashSet<>();

    protected boolean clearAll;

    public StreamCacheRealmProvider(StreamRealmCache cache, KeycloakSession session) {
        this.cache = cache;
        this.session = session;
        session.getTransaction().enlistPrepare(getPrepareTransaction());
        session.getTransaction().enlistAfterCompletion(getAfterTransaction());
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public MigrationModel getMigrationModel() {
        return getDelegate().getMigrationModel();
    }

    @Override
    public RealmProvider getDelegate() {
        if (!transactionActive) throw new IllegalStateException("Cannot access delegate without a transaction");
        if (delegate != null) return delegate;
        delegate = session.getProvider(RealmProvider.class);
        return delegate;
    }

    @Override
    public void registerRealmInvalidation(String id) {
        invalidations.add(id);
        cache.realmInvalidation(id, invalidations);
    }

    @Override
    public void registerClientInvalidation(String id) {
        invalidations.add(id);
        cache.clientInvalidation(id, invalidations);
    }
    @Override
    public void registerClientTemplateInvalidation(String id) {
        invalidations.add(id);
        cache.clientTemplateInvalidation(id, invalidations);
    }

    @Override
    public void registerRoleInvalidation(String id) {
        invalidations.add(id);
        cache.roleInvalidation(id, invalidations);
    }

    @Override
    public void registerGroupInvalidation(String id) {
        invalidations.add(id);
        cache.groupInvalidation(id, invalidations);
    }

    protected void runInvalidations() {
        for (String id : invalidations) {
            cache.invalidateObject(id);
        }
    }

    private KeycloakTransaction getPrepareTransaction() {
        return new KeycloakTransaction() {
            @Override
            public void begin() {
                transactionActive = true;
            }

            @Override
            public void commit() {
                /*  THIS WAS CAUSING DEADLOCK IN A CLUSTER
                if (delegate == null) return;
                List<String> locks = new LinkedList<>();
                locks.addAll(invalidations);

                Collections.sort(locks); // lock ordering
                cache.getRevisions().startBatch();

                if (!locks.isEmpty()) cache.getRevisions().getAdvancedCache().lock(locks);
                */

            }

            @Override
            public void rollback() {
                setRollbackOnly = true;
                transactionActive = false;
            }

            @Override
            public void setRollbackOnly() {
                setRollbackOnly = true;
            }

            @Override
            public boolean getRollbackOnly() {
                return setRollbackOnly;
            }

            @Override
            public boolean isActive() {
                return transactionActive;
            }
        };
    }

    private KeycloakTransaction getAfterTransaction() {
        return new KeycloakTransaction() {
            @Override
            public void begin() {
                transactionActive = true;
            }

            @Override
            public void commit() {
                try {
                    if (delegate == null) return;
                    if (clearAll) {
                        cache.clear();
                    }
                    runInvalidations();
                    transactionActive = false;
                } finally {
                    cache.endRevisionBatch();
                }
            }

            @Override
            public void rollback() {
                try {
                    setRollbackOnly = true;
                    runInvalidations();
                    transactionActive = false;
                } finally {
                    cache.endRevisionBatch();
                }
            }

            @Override
            public void setRollbackOnly() {
                setRollbackOnly = true;
            }

            @Override
            public boolean getRollbackOnly() {
                return setRollbackOnly;
            }

            @Override
            public boolean isActive() {
                return transactionActive;
            }
        };
    }

    @Override
    public RealmModel createRealm(String name) {
        RealmModel realm = getDelegate().createRealm(name);
        registerRealmInvalidation(realm.getId());
        return realm;
    }

    @Override
    public RealmModel createRealm(String id, String name) {
        RealmModel realm =  getDelegate().createRealm(id, name);
        registerRealmInvalidation(realm.getId());
        return realm;
    }

    @Override
    public RealmModel getRealm(String id) {
        CachedRealm cached = cache.get(id, CachedRealm.class);
        if (cached != null) {
            logger.tracev("by id cache hit: {0}", cached.getName());
        }
        if (cached == null) {
            Long loaded = cache.getCurrentRevision(id);
            RealmModel model = getDelegate().getRealm(id);
            if (model == null) return null;
            if (invalidations.contains(id)) return model;
            cached = new CachedRealm(loaded, model);
            cache.addRevisioned(cached);
        } else if (invalidations.contains(id)) {
            return getDelegate().getRealm(id);
        } else if (managedRealms.containsKey(id)) {
            return managedRealms.get(id);
        }
        RealmAdapter adapter = new RealmAdapter(cached, this);
        managedRealms.put(id, adapter);
        return adapter;
    }

    @Override
    public RealmModel getRealmByName(String name) {
        String cacheKey = getRealmByNameCacheKey(name);
        RealmListQuery query = cache.get(cacheKey, RealmListQuery.class);
        if (query != null) {
            logger.tracev("realm by name cache hit: {0}", name);
        }
        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            RealmModel model = getDelegate().getRealmByName(name);
            if (model == null) return null;
            if (invalidations.contains(model.getId())) return model;
            query = new RealmListQuery(loaded, cacheKey, model.getId());
            cache.addRevisioned(query);
            return model;
        } else if (invalidations.contains(cacheKey)) {
            return getDelegate().getRealmByName(name);
        } else {
            String realmId = query.getRealms().iterator().next();
            if (invalidations.contains(realmId)) {
                return getDelegate().getRealmByName(name);
            }
            return getRealm(realmId);
        }
    }

    public String getRealmByNameCacheKey(String name) {
        return "realm.query.by.name." + name;
    }

    @Override
    public List<RealmModel> getRealms() {
        // Retrieve realms from backend
        List<RealmModel> backendRealms = getDelegate().getRealms();

        // Return cache delegates to ensure cache invalidated during write operations
        List<RealmModel> cachedRealms = new LinkedList<RealmModel>();
        for (RealmModel realm : backendRealms) {
            RealmModel cached = getRealm(realm.getId());
            cachedRealms.add(cached);
        }
        return cachedRealms;
    }

    @Override
    public boolean removeRealm(String id) {
        RealmModel realm = getRealm(id);
        if (realm == null) return false;

        invalidations.add(getRealmClientsQueryCacheKey(id));
        invalidations.add(getRealmByNameCacheKey(realm.getName()));
        cache.invalidateObject(id);
        cache.realmRemoval(id, invalidations);
        return getDelegate().removeRealm(id);
    }

    @Override
    public ClientModel addClient(RealmModel realm, String clientId) {
        ClientModel client = getDelegate().addClient(realm, clientId);
        return addedClient(realm, client);
    }

    @Override
    public ClientModel addClient(RealmModel realm, String id, String clientId) {
        ClientModel client = getDelegate().addClient(realm, id, clientId);
        return addedClient(realm, client);
    }

    private ClientModel addedClient(RealmModel realm, ClientModel client) {
        logger.trace("added Client.....");
        // need to invalidate realm client query cache every time as it may not be loaded on this node, but loaded on another
        invalidations.add(getRealmClientsQueryCacheKey(realm.getId()));
        invalidations.add(client.getId());
        cache.clientAdded(realm.getId(), client.getId(), invalidations);
        // this is needed so that a new client that hasn't been committed isn't cached in a query
        listInvalidations.add(realm.getId());
        return client;
    }

    private String getRealmClientsQueryCacheKey(String realm) {
        return realm + REALM_CLIENTS_QUERY_SUFFIX;
    }

    private String getGroupsQueryCacheKey(String realm) {
        return realm + ".groups";
    }

    private String getTopGroupsQueryCacheKey(String realm) {
        return realm + ".top.groups";
    }

    private String getRolesCacheKey(String container) {
        return container + ROLES_QUERY_SUFFIX;
    }
    private String getRoleByNameCacheKey(String container, String name) {
        return container + "." + name + ROLES_QUERY_SUFFIX;
    }

    @Override
    public List<ClientModel> getClients(RealmModel realm) {
        String cacheKey = getRealmClientsQueryCacheKey(realm.getId());
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(realm.getId());
        if (queryDB) {
            return getDelegate().getClients(realm);
        }

        ClientListQuery query = cache.get(cacheKey, ClientListQuery.class);
        if (query != null) {
            logger.tracev("getClients cache hit: {0}", realm.getName());
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            List<ClientModel> model = getDelegate().getClients(realm);
            if (model == null) return null;
            Set<String> ids = new HashSet<>();
            for (ClientModel client : model) ids.add(client.getId());
            query = new ClientListQuery(loaded, cacheKey, realm, ids);
            logger.tracev("adding realm clients cache miss: realm {0} key {1}", realm.getName(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        List<ClientModel> list = new LinkedList<>();
        for (String id : query.getClients()) {
            ClientModel client = session.realms().getClientById(id, realm);
            if (client == null) {
                invalidations.add(cacheKey);
                return getDelegate().getClients(realm);
            }
            list.add(client);
        }
        return list;
    }


    @Override
    public boolean removeClient(String id, RealmModel realm) {
        ClientModel client = getClientById(id, realm);
        if (client == null) return false;
        // need to invalidate realm client query cache every time client list is changed
        invalidations.add(getRealmClientsQueryCacheKey(realm.getId()));
        invalidations.add(getClientByClientIdCacheKey(client.getClientId(), realm));
        listInvalidations.add(realm.getId());
        registerClientInvalidation(id);
        cache.clientRemoval(realm.getId(), id, invalidations);
        for (RoleModel role : client.getRoles()) {
            cache.roleInvalidation(role.getId(), invalidations);
        }
        return getDelegate().removeClient(id, realm);
    }

    @Override
    public void close() {
        if (delegate != null) delegate.close();
    }

    @Override
    public RoleModel addRealmRole(RealmModel realm, String name) {
        return addRealmRole(realm, KeycloakModelUtils.generateId(), name);
    }

    @Override
    public RoleModel addRealmRole(RealmModel realm, String id, String name) {
        invalidations.add(getRolesCacheKey(realm.getId()));
        // this is needed so that a new role that hasn't been committed isn't cached in a query
        listInvalidations.add(realm.getId());
        RoleModel role = getDelegate().addRealmRole(realm, name);
        invalidations.add(role.getId());
        return role;
    }

    @Override
    public Set<RoleModel> getRealmRoles(RealmModel realm) {
        String cacheKey = getRolesCacheKey(realm.getId());
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(realm.getId());
        if (queryDB) {
            return getDelegate().getRealmRoles(realm);
        }

        RoleListQuery query = cache.get(cacheKey, RoleListQuery.class);
        if (query != null) {
            logger.tracev("getRealmRoles cache hit: {0}", realm.getName());
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            Set<RoleModel> model = getDelegate().getRealmRoles(realm);
            if (model == null) return null;
            Set<String> ids = new HashSet<>();
            for (RoleModel role : model) ids.add(role.getId());
            query = new RoleListQuery(loaded, cacheKey, realm, ids);
            logger.tracev("adding realm roles cache miss: realm {0} key {1}", realm.getName(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        Set<RoleModel> list = new HashSet<>();
        for (String id : query.getRoles()) {
            RoleModel role = session.realms().getRoleById(id, realm);
            if (role == null) {
                invalidations.add(cacheKey);
                return getDelegate().getRealmRoles(realm);
            }
            list.add(role);
        }
        return list;
    }

    @Override
    public Set<RoleModel> getClientRoles(RealmModel realm, ClientModel client) {
        String cacheKey = getRolesCacheKey(client.getId());
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(client.getId());
        if (queryDB) {
            return getDelegate().getClientRoles(realm, client);
        }

        RoleListQuery query = cache.get(cacheKey, RoleListQuery.class);
        if (query != null) {
            logger.tracev("getClientRoles cache hit: {0}", client.getClientId());
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            Set<RoleModel> model = getDelegate().getClientRoles(realm, client);
            if (model == null) return null;
            Set<String> ids = new HashSet<>();
            for (RoleModel role : model) ids.add(role.getId());
            query = new RoleListQuery(loaded, cacheKey, realm, ids, client.getClientId());
            logger.tracev("adding client roles cache miss: client {0} key {1}", client.getClientId(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        Set<RoleModel> list = new HashSet<>();
        for (String id : query.getRoles()) {
            RoleModel role = session.realms().getRoleById(id, realm);
            if (role == null) {
                invalidations.add(cacheKey);
                return getDelegate().getClientRoles(realm, client);
            }
            list.add(role);
        }
        return list;
    }

    @Override
    public RoleModel addClientRole(RealmModel realm, ClientModel client, String name) {
        return addClientRole(realm, client, KeycloakModelUtils.generateId(), name);
    }

    @Override
    public RoleModel addClientRole(RealmModel realm, ClientModel client, String id, String name) {
        invalidations.add(getRolesCacheKey(client.getId()));
        // this is needed so that a new role that hasn't been committed isn't cached in a query
        listInvalidations.add(client.getId());
        RoleModel role = getDelegate().addClientRole(realm, client, id, name);
        invalidations.add(role.getId());
        return role;
    }

    @Override
    public RoleModel getRealmRole(RealmModel realm, String name) {
        String cacheKey = getRoleByNameCacheKey(realm.getId(), name);
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(realm.getId());
        if (queryDB) {
            return getDelegate().getRealmRole(realm, name);
        }

        RoleListQuery query = cache.get(cacheKey, RoleListQuery.class);
        if (query != null) {
            logger.tracev("getRealmRole cache hit: {0}.{1}", realm.getName(), name);
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            RoleModel model = getDelegate().getRealmRole(realm, name);
            if (model == null) return null;
            query = new RoleListQuery(loaded, cacheKey, realm, model.getId());
            logger.tracev("adding realm role cache miss: client {0} key {1}", realm.getName(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        RoleModel role = getRoleById(query.getRoles().iterator().next(), realm);
        if (role == null) {
            invalidations.add(cacheKey);
            return getDelegate().getRealmRole(realm, name);
        }
        return role;
    }

    @Override
    public RoleModel getClientRole(RealmModel realm, ClientModel client, String name) {
        String cacheKey = getRoleByNameCacheKey(client.getId(), name);
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(client.getId());
        if (queryDB) {
            return getDelegate().getClientRole(realm, client, name);
        }

        RoleListQuery query = cache.get(cacheKey, RoleListQuery.class);
        if (query != null) {
            logger.tracev("getClientRole cache hit: {0}.{1}", client.getClientId(), name);
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            RoleModel model = getDelegate().getClientRole(realm, client, name);
            if (model == null) return null;
            query = new RoleListQuery(loaded, cacheKey, realm, model.getId(), client.getClientId());
            logger.tracev("adding client role cache miss: client {0} key {1}", client.getClientId(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        RoleModel role = getRoleById(query.getRoles().iterator().next(), realm);
        if (role == null) {
            invalidations.add(cacheKey);
            return getDelegate().getClientRole(realm, client, name);
        }
        return role;
    }

    @Override
    public boolean removeRole(RealmModel realm, RoleModel role) {
        invalidations.add(getRolesCacheKey(role.getContainer().getId()));
        invalidations.add(getRoleByNameCacheKey(role.getContainer().getId(), role.getName()));
        listInvalidations.add(role.getContainer().getId());
        invalidations.add(role.getId());
        return getDelegate().removeRole(realm, role);
    }

    @Override
    public RoleModel getRoleById(String id, RealmModel realm) {
        CachedRole cached = cache.get(id, CachedRole.class);
        if (cached != null && !cached.getRealm().equals(realm.getId())) {
            cached = null;
        }

        if (cached == null) {
            Long loaded = cache.getCurrentRevision(id);
            RoleModel model = getDelegate().getRoleById(id, realm);
            if (model == null) return null;
            if (invalidations.contains(id)) return model;
            if (model.getContainer() instanceof ClientModel) {
                cached = new CachedClientRole(loaded, ((ClientModel) model.getContainer()).getId(), model, realm);
            } else {
                cached = new CachedRealmRole(loaded, model, realm);
            }
            cache.addRevisioned(cached);

        } else if (invalidations.contains(id)) {
            return getDelegate().getRoleById(id, realm);
        } else if (managedRoles.containsKey(id)) {
            return managedRoles.get(id);
        }
        RoleAdapter adapter = new RoleAdapter(cached,this, realm);
        managedRoles.put(id, adapter);
        return adapter;
    }

    @Override
    public GroupModel getGroupById(String id, RealmModel realm) {
        CachedGroup cached = cache.get(id, CachedGroup.class);
        if (cached != null && !cached.getRealm().equals(realm.getId())) {
            cached = null;
        }

        if (cached == null) {
            Long loaded = cache.getCurrentRevision(id);
            GroupModel model = getDelegate().getGroupById(id, realm);
            if (model == null) return null;
            if (invalidations.contains(id)) return model;
            cached = new CachedGroup(loaded, realm, model);
            cache.addRevisioned(cached);

        } else if (invalidations.contains(id)) {
            return getDelegate().getGroupById(id, realm);
        } else if (managedGroups.containsKey(id)) {
            return managedGroups.get(id);
        }
        GroupAdapter adapter = new GroupAdapter(cached, this, session, realm);
        managedGroups.put(id, adapter);
        return adapter;
    }

    @Override
    public void moveGroup(RealmModel realm, GroupModel group, GroupModel toParent) {
        registerGroupInvalidation(group.getId());
        if (toParent != null) registerGroupInvalidation(toParent.getId());
        getDelegate().moveGroup(realm, group, toParent);
    }

    @Override
    public List<GroupModel> getGroups(RealmModel realm) {
        String cacheKey = getGroupsQueryCacheKey(realm.getId());
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(realm.getId());
        if (queryDB) {
            return getDelegate().getGroups(realm);
        }

        GroupListQuery query = cache.get(cacheKey, GroupListQuery.class);
        if (query != null) {
            logger.tracev("getGroups cache hit: {0}", realm.getName());
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            List<GroupModel> model = getDelegate().getGroups(realm);
            if (model == null) return null;
            Set<String> ids = new HashSet<>();
            for (GroupModel client : model) ids.add(client.getId());
            query = new GroupListQuery(loaded, cacheKey, realm, ids);
            logger.tracev("adding realm getGroups cache miss: realm {0} key {1}", realm.getName(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        List<GroupModel> list = new LinkedList<>();
        for (String id : query.getGroups()) {
            GroupModel group = session.realms().getGroupById(id, realm);
            if (group == null) {
                invalidations.add(cacheKey);
                return getDelegate().getGroups(realm);
            }
            list.add(group);
        }
        return list;
    }

    @Override
    public List<GroupModel> getTopLevelGroups(RealmModel realm) {
        String cacheKey = getTopGroupsQueryCacheKey(realm.getId());
        boolean queryDB = invalidations.contains(cacheKey) || listInvalidations.contains(realm.getId());
        if (queryDB) {
            return getDelegate().getTopLevelGroups(realm);
        }

        GroupListQuery query = cache.get(cacheKey, GroupListQuery.class);
        if (query != null) {
            logger.tracev("getTopLevelGroups cache hit: {0}", realm.getName());
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            List<GroupModel> model = getDelegate().getTopLevelGroups(realm);
            if (model == null) return null;
            Set<String> ids = new HashSet<>();
            for (GroupModel client : model) ids.add(client.getId());
            query = new GroupListQuery(loaded, cacheKey, realm, ids);
            logger.tracev("adding realm getTopLevelGroups cache miss: realm {0} key {1}", realm.getName(), cacheKey);
            cache.addRevisioned(query);
            return model;
        }
        List<GroupModel> list = new LinkedList<>();
        for (String id : query.getGroups()) {
            GroupModel group = session.realms().getGroupById(id, realm);
            if (group == null) {
                invalidations.add(cacheKey);
                return getDelegate().getTopLevelGroups(realm);
            }
            list.add(group);
        }
        return list;
    }

    @Override
    public boolean removeGroup(RealmModel realm, GroupModel group) {
        registerGroupInvalidation(group.getId());
        listInvalidations.add(realm.getId());
        invalidations.add(getGroupsQueryCacheKey(realm.getId()));
        if (group.getParentId() == null) {
            invalidations.add(getTopGroupsQueryCacheKey(realm.getId()));
        } else {
            registerGroupInvalidation(group.getParentId());
        }
        return getDelegate().removeGroup(realm, group);
    }

    @Override
    public GroupModel createGroup(RealmModel realm, String name) {
        GroupModel group = getDelegate().createGroup(realm, name);
        return groupAdded(realm, group);
    }

    public GroupModel groupAdded(RealmModel realm, GroupModel group) {
        listInvalidations.add(realm.getId());
        invalidations.add(getGroupsQueryCacheKey(realm.getId()));
        invalidations.add(getTopGroupsQueryCacheKey(realm.getId()));
        invalidations.add(group.getId());
        return group;
    }

    @Override
    public GroupModel createGroup(RealmModel realm, String id, String name) {
        GroupModel group = getDelegate().createGroup(realm, id, name);
        return groupAdded(realm, group);
    }

    @Override
    public void addTopLevelGroup(RealmModel realm, GroupModel subGroup) {
        invalidations.add(getTopGroupsQueryCacheKey(realm.getId()));
        invalidations.add(subGroup.getId());
        if (subGroup.getParentId() != null) {
            registerGroupInvalidation(subGroup.getParentId());
        }
        getDelegate().addTopLevelGroup(realm, subGroup);

    }

    @Override
    public ClientModel getClientById(String id, RealmModel realm) {
        CachedClient cached = cache.get(id, CachedClient.class);
        if (cached != null && !cached.getRealm().equals(realm.getId())) {
            cached = null;
        }
        if (cached != null) {
            logger.tracev("client by id cache hit: {0}", cached.getClientId());
        }

        if (cached == null) {
            Long loaded = cache.getCurrentRevision(id);
            ClientModel model = getDelegate().getClientById(id, realm);
            if (model == null) return null;
            if (invalidations.contains(id)) return model;
            cached = new CachedClient(loaded, realm, model);
            logger.tracev("adding client by id cache miss: {0}", cached.getClientId());
            cache.addRevisioned(cached);
        } else if (invalidations.contains(id)) {
            return getDelegate().getClientById(id, realm);
        } else if (managedApplications.containsKey(id)) {
            return managedApplications.get(id);
        }
        ClientAdapter adapter = new ClientAdapter(realm, cached, this, null);
        managedApplications.put(id, adapter);
        return adapter;
    }

    @Override
    public ClientModel getClientByClientId(String clientId, RealmModel realm) {
        String cacheKey = getClientByClientIdCacheKey(clientId, realm);
        ClientListQuery query = cache.get(cacheKey, ClientListQuery.class);
        String id = null;

        if (query != null) {
            logger.tracev("client by name cache hit: {0}", clientId);
        }

        if (query == null) {
            Long loaded = cache.getCurrentRevision(cacheKey);
            ClientModel model = getDelegate().getClientByClientId(clientId, realm);
            if (model == null) return null;
            if (invalidations.contains(model.getId())) return model;
            id = model.getId();
            query = new ClientListQuery(loaded, cacheKey, realm, id);
            logger.tracev("adding client by name cache miss: {0}", clientId);
            cache.addRevisioned(query);
        } else if (invalidations.contains(cacheKey)) {
            return getDelegate().getClientByClientId(clientId, realm);
        } else {
            id = query.getClients().iterator().next();
            if (invalidations.contains(id)) {
                return getDelegate().getClientByClientId(clientId, realm);
            }
        }
        return getClientById(id, realm);
    }

    public String getClientByClientIdCacheKey(String clientId, RealmModel realm) {
        return realm.getId() + ".client.query.by.clientId." + clientId;
    }

    @Override
    public ClientTemplateModel getClientTemplateById(String id, RealmModel realm) {
        CachedClientTemplate cached = cache.get(id, CachedClientTemplate.class);
        if (cached != null && !cached.getRealm().equals(realm.getId())) {
            cached = null;
        }

        if (cached == null) {
            Long loaded = cache.getCurrentRevision(id);
            ClientTemplateModel model = getDelegate().getClientTemplateById(id, realm);
            if (model == null) return null;
            if (invalidations.contains(id)) return model;
            cached = new CachedClientTemplate(loaded, realm, model);
            cache.addRevisioned(cached);
        } else if (invalidations.contains(id)) {
            return getDelegate().getClientTemplateById(id, realm);
        } else if (managedClientTemplates.containsKey(id)) {
            return managedClientTemplates.get(id);
        }
        ClientTemplateModel adapter = new ClientTemplateAdapter(realm, cached, this);
        managedClientTemplates.put(id, adapter);
        return adapter;
    }

}
