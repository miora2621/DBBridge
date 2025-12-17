package stage.bici.DBBridge.TestIA;

import okhttp3.*;
import com.google.gson.*;
import java.io.IOException;

public class SqlViewTranslator {

    private static final String API_KEY = "";
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    public static String translateOracleToPostgres(String oracleSqlView) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        String prompt = """
        Tu es un traducteur SQL expert.
        Convertis **une vue Oracle** en **vue PostgreSQL fonctionnelle**, sans changer la logique.

        RÈGLES :
        - Garde même structure : SELECT, FROM, WHERE, GROUP BY, ORDER BY (pas de réorganisation).
        - Supprime FORCE et guillemets ("SCHEMA"."TABLE" → table).
        - NVL → COALESCE, SYSDATE → CURRENT_DATE.
        - Oracle (+) → JOIN explicite (LEFT JOIN, INNER JOIN…).
        - TOUTE sous-requête dans un FROM doit avoir un alias : (SELECT ...) AS sub.
        - Ajoute COALESCE(x,0) pour les opérations arithmétiques.
        - Si comparaison texte/int → ajoute cast (::INTEGER ou ::TEXT).
        - Corrige les erreurs de type COALESCE entre text et integer (tout homogène).
        - Alias uniques : pas deux identiques (renomme si besoin).
        - Supprime ou ignore les schémas inexistants.
        - Ne renvoie **que le SQL PostgreSQL**, sans explication ni texte.

        Format final :
        CREATE OR REPLACE VIEW nom_vue AS
        <SQL PostgreSQL corrigé>

        Vue Oracle :
        """ + oracleSqlView;



        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-4o-mini"); // Modèle plus puissant et précis
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);

        RequestBody requestBody = RequestBody.create(
            body.toString(),
            MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer " + API_KEY)
            .post(requestBody)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Erreur API: " + response.code() + " - " + response.message());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new IOException("Réponse vide de l'API");
            }

            JsonObject messageObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = messageObj.get("content").getAsString();

            // Nettoyage du contenu
            content = content.replaceAll("(?s)```sql|```", "").trim();

            return content;
        }
    }

    public static String translatePostgresToOracle(String postgresSqlView) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        String prompt = """
        Tu es un traducteur SQL expert.
        Convertis **une vue PostgreSQL** en **vue Oracle 11g fonctionnelle**, sans changer la logique.

        RÈGLES :
        - Garde même structure : SELECT, FROM, WHERE, GROUP BY, ORDER BY (pas de réorganisation).
        - Les noms Oracle sont limités à 30 caractères maximum (tronque si nécessaire).
        - garde tout le view mais juste adapte la syntaxe pour avoir une version oracle qui va faire exactement pareil.
        -c'est très important de garder la même logique.
        -ne repond que par le code sql oracle sans explication ni texte.

        Format final :
        CREATE OR REPLACE VIEW nom_vue AS
        <SQL Oracle corrigé>

        Vue PostgreSQL :
        """ + postgresSqlView;

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-4o-mini"); // Modèle plus puissant et précis
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);

        RequestBody requestBody = RequestBody.create(
            body.toString(),
            MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer " + API_KEY)
            .post(requestBody)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Erreur API: " + response.code() + " - " + response.message());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new IOException("Réponse vide de l'API");
            }

            JsonObject messageObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = messageObj.get("content").getAsString();

            // Nettoyage du contenu
            content = content.replaceAll("(?s)```sql|```", "").trim();

            return content;
        }
    }

    public static void main(String[] args) throws IOException {
      
    }
}