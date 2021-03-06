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
package org.keycloak.testsuite;

import java.io.File;
import org.keycloak.testsuite.arquillian.TestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.NotFoundException;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RealmsResource;
import org.keycloak.models.Constants;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.arquillian.AuthServerTestEnricher;
import org.keycloak.testsuite.arquillian.SuiteContext;
import org.keycloak.testsuite.auth.page.WelcomePage;
import org.keycloak.testsuite.util.OAuthClient;
import org.openqa.selenium.WebDriver;
import org.keycloak.testsuite.auth.page.AuthServer;
import org.keycloak.testsuite.auth.page.AuthServerContextRoot;
import org.keycloak.testsuite.auth.page.AuthRealm;
import static org.keycloak.testsuite.auth.page.AuthRealm.ADMIN;
import static org.keycloak.testsuite.auth.page.AuthRealm.MASTER;
import org.keycloak.testsuite.auth.page.account.Account;
import org.keycloak.testsuite.auth.page.login.OIDCLogin;
import org.keycloak.testsuite.auth.page.login.UpdatePassword;
import org.keycloak.testsuite.util.Timer;
import org.keycloak.testsuite.util.WaitUtils;
import static org.keycloak.testsuite.admin.Users.setPasswordFor;
import static org.keycloak.testsuite.admin.Users.setPasswordFor;

/**
 *
 * @author tkyjovsk
 */
@RunWith(Arquillian.class)
@RunAsClient
public abstract class AbstractKeycloakTest {

    protected Logger log = Logger.getLogger(this.getClass());

    @ArquillianResource
    protected SuiteContext suiteContext;

    @ArquillianResource
    protected TestContext testContext;

    protected Keycloak adminClient;

    protected OAuthClient oauthClient;

    protected List<RealmRepresentation> testRealmReps;

    @Drone
    protected WebDriver driver;

    @Page
    protected AuthServerContextRoot authServerContextRootPage;
    @Page
    protected AuthServer authServerPage;

    @Page
    protected AuthRealm masterRealmPage;

    @Page
    protected Account accountPage;
    @Page
    protected OIDCLogin loginPage;
    @Page
    protected UpdatePassword updatePasswordPage;

    @Page
    protected WelcomePage welcomePage;

    protected UserRepresentation adminUser;

    @Before
    public void beforeAbstractKeycloakTest() {
        adminClient = Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth",
                MASTER, ADMIN, ADMIN, Constants.ADMIN_CLI_CLIENT_ID);
        oauthClient = new OAuthClient(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth");

        
        adminUser = createAdminUserRepresentation();

        setDefaultPageUriParameters();

        driverSettings();

        if (!suiteContext.isAdminPasswordUpdated()) {
            log.debug("updating admin password");
            updateMasterAdminPassword();
            suiteContext.setAdminPasswordUpdated(true);
        }

        importTestRealms();
    }

    @After
    public void afterAbstractKeycloakTest() {
//        removeTestRealms(); // keeping test realms after test to be able to inspect failures, instead deleting existing realms before import
//        keycloak.close(); // keeping admin connection open
    }

    private void updateMasterAdminPassword() {
        welcomePage.navigateTo();
        if (!welcomePage.isPasswordSet()) {
            welcomePage.setPassword("admin", "admin");
        }
    }

    public void deleteAllCookiesForMasterRealm() {
        masterRealmPage.navigateTo();
        log.debug("deleting cookies in master realm");
        driver.manage().deleteAllCookies();
    }

    protected void driverSettings() {
        driver.manage().timeouts().pageLoadTimeout(WaitUtils.PAGELOAD_TIMEOUT, TimeUnit.MILLISECONDS);
        driver.manage().window().maximize();
    }

    public void setDefaultPageUriParameters() {
        masterRealmPage.setAuthRealm(MASTER);
        loginPage.setAuthRealm(MASTER);
    }

    public abstract void addTestRealms(List<RealmRepresentation> testRealms);

    private void addTestRealms() {
        log.debug("loading test realms");
        if (testRealmReps == null) {
            testRealmReps = new ArrayList<>();
        }
        if (testRealmReps.isEmpty()) {
            addTestRealms(testRealmReps);
        }
    }

    public void importTestRealms() {
        addTestRealms();
        log.info("importing test realms");
        for (RealmRepresentation testRealm : testRealmReps) {
            importRealm(testRealm);
        }
    }

    public void removeTestRealms() {
        log.info("removing test realms");
        for (RealmRepresentation testRealm : testRealmReps) {
            removeRealm(testRealm);
        }
    }

    private UserRepresentation createAdminUserRepresentation() {
        UserRepresentation adminUserRep = new UserRepresentation();
        adminUserRep.setUsername(ADMIN);
        setPasswordFor(adminUserRep, ADMIN);
        return adminUserRep;
    }

    public void importRealm(RealmRepresentation realm) {
        log.debug("importing realm: " + realm.getRealm());
        try { // TODO - figure out a way how to do this without try-catch
            RealmResource realmResource = adminClient.realms().realm(realm.getRealm());
            RealmRepresentation rRep = realmResource.toRepresentation();
            log.debug("realm already exists on server, re-importing");
            realmResource.remove();
        } catch (NotFoundException nfe) {
            // expected when realm does not exist
        }
        adminClient.realms().create(realm);
    }

    public void removeRealm(RealmRepresentation realm) {
        adminClient.realms().realm(realm.getRealm()).remove();
    }
    
    public RealmsResource realmsResouce() {
        return adminClient.realms();
    }

}
