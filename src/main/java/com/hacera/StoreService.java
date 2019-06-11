// (c) 2019 The Unbounded Network LTD
package com.hacera;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public class StoreService {
    
    // we are storing users manually here.
    // in theory, Fabric has a class that integrates with CA client that also handles user storage...
    static final String STORE_PATH = "./hf-user-store";
    static void initUserStore() {
        
        // check if directory exists
        File f = new File(STORE_PATH);
        if (!f.exists()) {
            if (!f.mkdir()) {
                throw new RuntimeException(String.format("Failed to create user store directory at %s", STORE_PATH));
            }
        } else if (!f.isDirectory()) {
            throw new RuntimeException(String.format("User store path %s exists, but is not a directory", STORE_PATH));
        }
        
    }
    
    public static FabricUser getUserFromStore(String name) throws IOException {
        
        File f = new File(String.format("%s/%s", STORE_PATH, name));
        if (!f.exists()) {
            return null;
        }
        
        String jsonData = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        try {
            
            FabricUser user = FabricUser.unserializeJSON(jsonData);
            return user;
            
        } catch (ClassNotFoundException x) {
            throw new RuntimeException(x);
        }
        
    }
    
    public static void putUserToStore(FabricUser user) throws IOException {
        
        File f = new File(String.format("%s/%s", STORE_PATH, user.getName()));
        String jsonData = user.serializeJSON();
        Files.write(f.toPath(), jsonData.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
    }
    
}
