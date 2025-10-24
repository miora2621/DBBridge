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

        // Requête JSON pour le modèle GPT
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", "Convertis cette vue Oracle en PostgreSQL :\n\n" + oracleSqlView + "\"Ta réponse doit contenir UNIQUEMENT la requête SQL valide, sans explication, sans texte, sans commentaire, sans Markdown.Enleve le double cote avant et apres le nom de la view , enleve aussi le nom de la table et le point avant le nom de la view a creer\"");

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-4o-mini"); // modèle rapide et économique
        body.add("messages", messages);

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
                throw new IOException("Erreur API: " + response);
            }
            JsonObject jsonResponse = JsonParser.parseString(response.body().string()).getAsJsonObject();
            return jsonResponse
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }
    }

    public static void main(String[] args) throws IOException {
        String oracleView = "\r\n" + //
                        "  CREATE OR REPLACE FORCE VIEW \"REFERE\".\"FACTETUSANSSEM_LIBCPL\" (\"ID\", \"DESIGNATION\", \"IDETUDIANT\", \"ECHEANCEPAIEMENT\", \"DATY\", \"ETAT\", \"NOM\", \"PRENOM\", \"MONTANT\", \"PAYE\", \"RESTE\", \"ETATLIB\", \"PROMOTION\", \"NUMERO\", \"MATIERELIB\", \"IDPROMOTION\") AS \r\n" + //
                        "  SELECT\r\n" + //
                        "        F.ID,\r\n" + //
                        "        F.DESIGNATION,\r\n" + //
                        "        F.IDETUDIANT,\r\n" + //
                        "        F.ECHEANCEPAIEMENT,\r\n" + //
                        "        F.DATY,\r\n" + //
                        "        F.ETAT,\r\n" + //
                        "        E.NOM,\r\n" + //
                        "        E.PRENOM,\r\n" + //
                        "        FG.MONTANT                                                                                    AS MONTANT,\r\n" + //
                        "        CAST(NVL(S.MONTANT, 0) AS NUMBER(30, 2))                                                      AS PAYE,\r\n" + //
                        "        CAST(NVL(FG.MONTANT, 0)-NVL(S.MONTANT, 0) AS NUMBER(30, 2))                                   AS RESTE,\r\n" + //
                        "        CASE\r\n" + //
                        "            WHEN F.ETAT =1 THEN\r\n" + //
                        "                'CREE'\r\n" + //
                        "            WHEN F.ETAT=11 THEN\r\n" + //
                        "                'VISEE'\r\n" + //
                        "            WHEN F.ETAT= 0 THEN\r\n" + //
                        "                'ANNULEE'\r\n" + //
                        "        END AS ETATLIB,\r\n" + //
                        "        ENT.PROMOTION,\r\n" + //
                        "        ENT.NUMERO,\r\n" + //
                        "        FG.MATIERELIB,\r\n" + //
                        "        ENT.IDPROMOTION\r\n" + //
                        "\r\n" + //
                        "    FROM\r\n" + //
                        "        FACTSCOLAVECETU            F,\r\n" + //
                        "        FACTSCOLAVECETUDETAIL_GRPMTT  FG,\r\n" + //
                        "        SUMPAIEMENTFACTSCOLAVECETU S,\r\n" + //
                        "        ETUDIANT                   E,\r\n" + //
                        "        ENTREEUNIVLIB              ENT\r\n" + //
                        "    WHERE\r\n" + //
                        "        F.ID = S.IDFACTSCOLAVECETU(+)\r\n" + //
                        "        AND F.ID=FG.IDFACTSCOLAVECETU(+)\r\n" + //
                        "        AND F.IDETUDIANT =E.ID\r\n" + //
                        "        AND E.ID=ENT.IDETUDIANT\r\n" + //
                        "\r\n" + //
                        "";
        String postgresView = translateOracleToPostgres(oracleView);
        System.out.println("Vue PostgreSQL :\n" + postgresView);
    }
}