package com.termux.app.owlia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Information about an AI provider for authentication setup.
 */
public class ProviderInfo {
    
    public enum AuthMethod {
        API_KEY,        // Simple API key paste
        SETUP_TOKEN,    // Anthropic setup token
        OAUTH           // OAuth browser flow
    }
    
    private final String id;
    private final String name;
    private final String description;
    private final List<AuthMethod> authMethods;
    private final boolean recommended;
    
    public ProviderInfo(String id, String name, String description, 
                       List<AuthMethod> authMethods, boolean recommended) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.authMethods = authMethods;
        this.recommended = recommended;
    }
    
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public List<AuthMethod> getAuthMethods() {
        return authMethods;
    }
    
    public boolean isRecommended() {
        return recommended;
    }
    
    /**
     * Get the list of supported providers
     */
    public static List<ProviderInfo> getPopularProviders() {
        List<ProviderInfo> providers = new ArrayList<>();
        
        providers.add(new ProviderInfo(
            "anthropic",
            "Anthropic (Claude)",
            "Setup Token or API Key",
            Arrays.asList(AuthMethod.SETUP_TOKEN, AuthMethod.API_KEY),
            true // recommended
        ));
        
        providers.add(new ProviderInfo(
            "openai",
            "OpenAI",
            "API Key or OAuth",
            Arrays.asList(AuthMethod.API_KEY, AuthMethod.OAUTH),
            false
        ));
        
        providers.add(new ProviderInfo(
            "google",
            "Google (Gemini)",
            "API Key or OAuth",
            Arrays.asList(AuthMethod.API_KEY, AuthMethod.OAUTH),
            false
        ));
        
        providers.add(new ProviderInfo(
            "openrouter",
            "OpenRouter",
            "API Key (any model)",
            Arrays.asList(AuthMethod.API_KEY),
            false
        ));
        
        return providers;
    }
    
    /**
     * Get additional providers (shown when "More" is expanded)
     */
    public static List<ProviderInfo> getMoreProviders() {
        List<ProviderInfo> providers = new ArrayList<>();
        
        providers.add(new ProviderInfo(
            "kimi",
            "Kimi",
            "API Key",
            Arrays.asList(AuthMethod.API_KEY),
            false
        ));
        
        providers.add(new ProviderInfo(
            "minimax",
            "MiniMax",
            "API Key",
            Arrays.asList(AuthMethod.API_KEY),
            false
        ));
        
        providers.add(new ProviderInfo(
            "venice",
            "Venice",
            "API Key",
            Arrays.asList(AuthMethod.API_KEY),
            false
        ));
        
        providers.add(new ProviderInfo(
            "chutes",
            "Chutes",
            "OAuth",
            Arrays.asList(AuthMethod.OAUTH),
            false
        ));
        
        return providers;
    }
}
