package de.evoila.cf.cpi.bosh;

import de.evoila.cf.broker.bean.BoshProperties;
import de.evoila.cf.broker.custom.postgres.PostgreSQLUtils;
import de.evoila.cf.broker.model.catalog.plan.Plan;
import de.evoila.cf.broker.model.ServiceInstance;
import de.evoila.cf.broker.model.credential.PasswordCredential;
import de.evoila.cf.broker.model.credential.UsernamePasswordCredential;
import de.evoila.cf.broker.util.MapUtils;
import de.evoila.cf.cpi.CredentialConstants;
import de.evoila.cf.cpi.bosh.deployment.DeploymentManager;
import de.evoila.cf.cpi.bosh.deployment.manifest.Manifest;
import de.evoila.cf.security.credentials.CredentialStore;
import de.evoila.cf.security.credentials.DefaultCredentialConstants;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;
import java.security.SecureRandom;
import javax.xml.bind.DatatypeConverter;

/**
 * @author Marco Hennig, Johannes Hiemer.
 */
@Component
public class PostgresDeploymentManager extends DeploymentManager {

    private CredentialStore credentialStore;

    public PostgresDeploymentManager(BoshProperties properties,
                                     Environment environment,
                                     CredentialStore credentialStore) {
        super(properties, environment);
        this.credentialStore = credentialStore;
    }

    @Override
    protected void replaceParameters(ServiceInstance serviceInstance, Manifest manifest, Plan plan, Map<String,
            Object> customParameters, boolean isUpdate) {
	SecureRandom random = new SecureRandom();
	boolean useSsl = true;
	ArrayList<String> extensions = null;
	byte tdeBytes[] = new byte[16]; // 128 bits are converted to 16 bytes;
	random.nextBytes(tdeBytes);
	String tdeKeyString = DatatypeConverter.printHexBinary(tdeBytes).toLowerCase();
        HashMap<String, Object> properties = new HashMap<>();
        if (customParameters != null && !customParameters.isEmpty()){
            properties.putAll(customParameters);
            Object ssl=getMapProperty(properties,"postgres", "ssl", "enabled");
            if (ssl!=null) {
                useSsl=((Boolean)ssl).booleanValue();
                ssl=getMapProperty(properties,"postgres", "ssl");
            }
            extensions=(ArrayList<String>)getMapProperty(properties, "postgres","database","extensions");
            if (extensions != null){
                deleteMapProperty(properties, "postgres","database","extensions");
            }
        }

        if (customParameters != null && !customParameters.isEmpty()) {
            for (Map.Entry parameter : customParameters.entrySet()) {
                Map<String, Object> manifestProperties = manifestProperties(parameter.getKey().toString(), manifest);

                if (manifestProperties != null)
                    MapUtils.deepMerge(manifestProperties, customParameters);
            }

        }
        
//        if (!isUpdate) {
            log.debug("Updating Deployment Manifest, replacing parameters");

            Map<String, Object> postgresManifestProperties = manifestProperties("postgres", manifest);

            HashMap<String, Object> postgres = (HashMap<String, Object>) postgresManifestProperties.get("postgres");
            HashMap<String, Object> postgresExporter = (HashMap<String, Object>) postgresManifestProperties.get("postgres_exporter");
            HashMap<String, Object> backupAgent = (HashMap<String, Object>) postgresManifestProperties.get("backup_agent");

            List<HashMap<String, Object>> adminUsers = (List<HashMap<String, Object>>) postgres.get("admin_users");
            HashMap<String, Object> userProperties = adminUsers.get(0);
            UsernamePasswordCredential usernamePasswordCredential = credentialStore.createUser(serviceInstance, CredentialConstants.ROOT_CREDENTIALS);
            userProperties.put("username", usernamePasswordCredential.getUsername());
            userProperties.put("password", usernamePasswordCredential.getPassword());
            serviceInstance.setUsername(usernamePasswordCredential.getUsername());

            UsernamePasswordCredential exporterCredential = credentialStore.createUser(serviceInstance,
                    DefaultCredentialConstants.EXPORTER_CREDENTIALS);
            postgresExporter.put("datasource_name", "postgresql://" + exporterCredential.getUsername() + ":" + exporterCredential.getPassword() + "@127.0.0.1:5432/postgres?sslmode="+(useSsl?"require":"disable"));
            HashMap<String, Object> exporterProperties = adminUsers.get(1);
            exporterProperties.put("username", exporterCredential.getUsername());
            exporterProperties.put("password", exporterCredential.getPassword());


            UsernamePasswordCredential backupAgentUsernamePasswordCredential = credentialStore.createUser(serviceInstance,
                    DefaultCredentialConstants.BACKUP_AGENT_CREDENTIALS);
            backupAgent.put("username", backupAgentUsernamePasswordCredential.getUsername());
            backupAgent.put("password", backupAgentUsernamePasswordCredential.getPassword());

            List<HashMap<String, Object>> backupUsers = (List<HashMap<String, Object>>) postgres.get("backup_users");
            HashMap<String, Object> backupUserProperties = backupUsers.get(0);
            UsernamePasswordCredential backupUsernamePasswordCredential = credentialStore.createUser(serviceInstance,
                    DefaultCredentialConstants.BACKUP_CREDENTIALS);
            backupUserProperties.put("username", backupUsernamePasswordCredential.getUsername());
            backupUserProperties.put("password", backupUsernamePasswordCredential.getPassword());

            List<HashMap<String, Object>> users = (List<HashMap<String, Object>>) postgres.get("users");
            HashMap<String, Object> defaultUserProperties = users.get(0);
            UsernamePasswordCredential defaultUsernamePasswordCredential = credentialStore.createUser(serviceInstance,
                    CredentialConstants.USER_CREDENTIALS);
            defaultUserProperties.put("username", defaultUsernamePasswordCredential.getUsername());
            defaultUserProperties.put("password", defaultUsernamePasswordCredential.getPassword());

            List<String> databaseUsers = new ArrayList<>();
            databaseUsers.add(defaultUsernamePasswordCredential.getUsername());

            List<Map<String, Object>> databases = (ArrayList<Map<String,Object>>)getMapProperty(postgres, "postgres","databases");
            if (databases == null) {
                databases = new ArrayList<>();
            }
            Map<String, Object> database = new HashMap<>();
            database.put("name", PostgreSQLUtils.dbName(serviceInstance.getId()));
            database.put("users", databaseUsers);
            if(extensions!=null && !extensions.isEmpty()){
                database.put("extensions",extensions);
            }
            databases.add(database);

            postgres.put("databases", databases);
  //      }


        this.updateInstanceGroupConfiguration(manifest, plan);
    }

    private Map<String, Object> manifestProperties(String instanceGroup, Manifest manifest) {
        return manifest
            .getInstanceGroups()
            .stream()
            .filter(i -> {
                if (i.getName().equals(instanceGroup))
                    return true;
                return false;
            }).findFirst().get().getProperties();
    }

    private Object getMapProperty(Map<String,Object> map,String ... keys){
        Map<String,Object> nextMap=map;
         Object objectMap=map;
        if(map==null){
            return null;
        }
        for(String key:keys){
            map=(Map< String, Object>)objectMap;
            if(!map.containsKey(key)){
                return null;
            }
            objectMap=map.get(key);
        }
        return objectMap;
    }

    private void deleteMapProperty(Map<String,Object> map,String ... keys){
        Map<String,Object> nextMap=map;
        Object objectMap = map;
        if(map==null){
            return;
        }
        for(String key:keys){
            map=(Map< String, Object>)objectMap;
            if(!map.containsKey(key)){
                return ;
            }
            objectMap=map.get(key);
        }
        map.remove(objectMap);
    }

    private void setMapProperty(Map<String,Object> map,Object value,String ... keys){
        Map<String,Object> nextMap=map;
        int i;
        for(i=0;i<keys.length-1;i++){
            if(!map.containsKey(keys[i])){
                map.put(keys[i],keys[i+1]);
            }else {
                map = (Map<String, Object>) map.get(keys[i]);
            }
        }
        map.put(keys[i],value);
    }
}
