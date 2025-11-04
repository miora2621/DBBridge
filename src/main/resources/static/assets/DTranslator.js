import { pipeline } from '@xenova/transformers';

/**
 * Traduit un tableau de mots en utilisant Xenova/Transformers.js
 * @param {string[]} words - Tableau de mots à traduire
 * @param {string} sourceLang - Langue source (ex: 'en', 'fr', 'es', 'de', 'it', 'pt')
 * @param {string} targetLang - Langue cible (ex: 'en', 'fr', 'es', 'de', 'it', 'pt')
 * @returns {Promise<string[]>} - Tableau de mots traduits
 */
async function translateWords(words, sourceLang = 'en', targetLang = 'fr') {
    try {
        // Construire le nom du modèle
        const modelName = `Xenova/opus-mt-${sourceLang}-${targetLang}`;

        // Initialiser le pipeline de traduction
        const translator = await pipeline('translation', modelName);

        // Traduire chaque mot
        const translations = await Promise.all(
            words.map(async (word) => {
                const result = await translator(word.trim());
                return result[0].translation_text;
            })
        );

        return translations;
    } catch (error) {
        console.error('Erreur lors de la traduction:', error);
        throw error;
    }
}

export { translateWords };
