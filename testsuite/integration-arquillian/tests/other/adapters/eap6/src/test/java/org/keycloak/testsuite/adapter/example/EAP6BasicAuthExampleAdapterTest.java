package org.keycloak.testsuite.adapter.example;

import org.keycloak.testsuite.arquillian.annotation.AdapterLibsLocationProperty;
import org.keycloak.testsuite.arquillian.annotation.AppServerContainer;

/**
 *
 * @author tkyjovsk
 */
@AppServerContainer("app-server-eap6")
@AdapterLibsLocationProperty("adapter.libs.eap6")
public class EAP6BasicAuthExampleAdapterTest extends AbstractBasicAuthExampleAdapterTest {

}
