function formatText(str){
    return str.replace(/"/g, '\\"');
}
function escapeHtmlEntities(str) {
    return str.replace(/[\u00A0-\u9999<>&]/gim, function(i) {
        return '&#'+i.charCodeAt(0)+';';
    });
}

function getMultiLangElements() {
    // Sélectionne toutes les balises qui ont l'attribut "multi-lang"
    const elements = document.querySelectorAll('[multi-lang]');
    return Array.from(elements);
}

async function getTranslations(translationLanguage, mots) {
    try {
        const response = await fetch(
            "http://localhost:8082/DS_Studio_war_exploded/traduire.MainController?langue=" + translationLanguage,
            {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify([...mots])
            }
        );
        const data = await response.json();
        console.log(data); // ["Padre","Madre","Bambino"]
        return data;
    } catch (error) {
        console.error("Erreur lors de la traduction:", error);
        console.log("teny "+JSON.stringify(mots));
        return mots; // retourne les mots d'origine si erreur
    }
}

async function translatePage(language) {
    const elements = getMultiLangElements(); // tes éléments avec multi-lang
    const mots = elements.map(el => formatText(escapeHtmlEntities(el.innerHTML)));
    console.log("teny "+mots);
    const translations = await getTranslations(language, mots);

    for (let i = 0; i < elements.length; i++) {
        elements[i].innerHTML = translations[i];
    }
}

async function changeLanguage(selectElement) {
    console.log(selectElement.value);
    await translatePage(selectElement.value);
}

function runLast(fn) {
    // Première étape : microtask
    queueMicrotask(() => {
        // Deuxième étape : macrotask (setTimeout)
        setTimeout(() => {
            fn();
        }, 0);
    });
}
// Exemple d'utilisation :
document.addEventListener('DOMContentLoaded', () => {

});


