package com.example.languageteacherbot.service;

import com.example.languageteacherbot.entity.User;
import com.example.languageteacherbot.entity.UserFavoriteWord;
import com.example.languageteacherbot.entity.Word;
import com.example.languageteacherbot.entity.UserWord;
import com.example.languageteacherbot.repository.UserFavoriteWordRepository;
import com.example.languageteacherbot.repository.UserRepository;
import com.example.languageteacherbot.repository.WordRepository;
import com.example.languageteacherbot.repository.UserWordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import com.example.languageteacherbot.entity.Sentence;
import com.example.languageteacherbot.repository.SentenceRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@EnableScheduling
public class TelegramService {

    @Value("${telegram.bot.token}")
    private String botToken;

    private final String SEND_MESSAGE_URL = "https://api.telegram.org/bot";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WordRepository wordRepository;

    @Autowired
    private UserWordRepository userWordRepository;

    @Autowired
    private UserFavoriteWordRepository userFavoriteWordRepository;

    @Autowired
    private SentenceRepository sentenceRepository;

    private final Map<Long, ConversationState> userStates = new HashMap<>();
    private final Map<Long, FlashcardGameSession> activeFlashcardGames = new HashMap<>();
    private final Map<Long, SentenceGameSession> activeSentenceGames = new HashMap<>();
    private final Map<Long, Map<String, Long>> userWordDeleteMap = new HashMap<>();
    private final Map<Long, Integer> userDictionaryPage = new ConcurrentHashMap<>();
    private final Map<Long, String> currentMyWordsSection = new ConcurrentHashMap<>();
    private final Map<Long, String> userDictionaryLevel = new ConcurrentHashMap<>();

    private final AtomicLong lastUpdateId = new AtomicLong(0L);
    private final RestTemplate restTemplate;
    private volatile boolean isRunning = false;
    private volatile boolean webhookDeleted = false;

    public TelegramService() {
        this.restTemplate = new RestTemplate();
    }

    private void logSafe(String message) {
        String safeMessage = message.replace(botToken, "***");
        System.out.println(safeMessage);
    }

    private void logErrorSafe(String message, Exception e) {
        String safeMessage = message.replace(botToken, "***");
        System.err.println(safeMessage + ": " + e.getMessage());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeBot() {
        deleteWebhook();
    }

    private void deleteWebhook() {
        try {
            String url = SEND_MESSAGE_URL + botToken + "/deleteWebhook";
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                null, 
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            if (response.getBody() != null && Boolean.TRUE.equals(response.getBody().get("ok"))) {
                System.out.println("Webhook successfully deleted");
                webhookDeleted = true;
                startPolling();
            } else {
                System.err.println("Failed to delete webhook: " + response.getBody());
            }
        } catch (Exception e) {
            System.err.println("Error deleting webhook: " + e.getMessage());
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    deleteWebhook();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void pollUpdates() {
        if (!isRunning || !webhookDeleted) return;

        try {
            String url = SEND_MESSAGE_URL + botToken + "/getUpdates?offset=" + (lastUpdateId.get() + 1) + "&timeout=30";
            
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                null, 
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            if (response.getBody() != null && response.getBody().containsKey("result")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> updates = (List<Map<String, Object>>) response.getBody().get("result");
                
                if (updates != null && !updates.isEmpty()) {
                    for (Map<String, Object> update : updates) {
                        Long updateId = ((Number) update.get("update_id")).longValue();
                        lastUpdateId.set(updateId);

                        processUpdate(update);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error polling updates: " + e.getMessage());

            if (e.getMessage() != null && e.getMessage().contains("409")) {
                webhookDeleted = false;
                deleteWebhook();
            }
        }
    }

    public void startPolling() {
        isRunning = true;
        System.out.println("Polling started");
    }

    public void stopPolling() {
        isRunning = false;
        System.out.println("Polling stopped");
    }

    public void sendMessage(Long chatId, String text) {
        sendMessageWithButtons(chatId, text, null);
    }

    private void sendMessageWithButtons(Long chatId, String text, List<List<String>> buttons) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");

            if (buttons != null && !buttons.isEmpty()) {
                List<List<Map<String, Object>>> keyboard = new ArrayList<>();
                for (List<String> row : buttons) {
                    List<Map<String, Object>> keyboardRow = new ArrayList<>();
                    for (String buttonText : row) {
                        Map<String, Object> button = new HashMap<>();
                        button.put("text", buttonText);
                        keyboardRow.add(button);
                    }
                    keyboard.add(keyboardRow);
                }

                Map<String, Object> replyMarkup = new HashMap<>();
                replyMarkup.put("keyboard", keyboard);
                replyMarkup.put("resize_keyboard", true);
                replyMarkup.put("one_time_keyboard", false);
                request.put("reply_markup", replyMarkup);
            }

            restTemplate.postForObject(SEND_MESSAGE_URL + botToken + "/sendMessage", request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    public void processUpdate(Map<String, Object> update) {
        try {
            if (update.containsKey("callback_query")) {
                Map<String, Object> callbackQuery = (Map<String, Object>) update.get("callback_query");
                String data = (String) callbackQuery.get("data");
                Map<String, Object> message = (Map<String, Object>) callbackQuery.get("message");
                Long chatId = ((Number) ((Map<String, Object>) message.get("chat")).get("id")).longValue();
                Integer messageId = ((Number) message.get("message_id")).intValue();

                if (data.startsWith("dict_prev:")) {
                    String[] parts = data.split(":");
                    int page = Integer.parseInt(parts[1]);
                    String level = parts[2];
                    userDictionaryPage.put(chatId, page);
                    userDictionaryLevel.put(chatId, level);
                    editMessageWithDictionary(chatId, messageId);
                } else if (data.startsWith("dict_next:")) {
                    String[] parts = data.split(":");
                    int page = Integer.parseInt(parts[1]);
                    String level = parts[2];
                    userDictionaryPage.put(chatId, page);
                    userDictionaryLevel.put(chatId, level);
                    editMessageWithDictionary(chatId, messageId);
                } else if (data.startsWith("mywords_prev:")) {
                    int page = Integer.parseInt(data.split(":")[1]);
                    userDictionaryPage.put(chatId, page);
                    String section = data.split(":")[2];
                    currentMyWordsSection.put(chatId, section);
                    if ("unknown".equals(section)) {
                        editMessageWithMyWords(chatId, messageId);
                    } else {
                        editMessageWithFavoriteWords(chatId, messageId);
                    }
                } else if (data.startsWith("mywords_next:")) {
                    int page = Integer.parseInt(data.split(":")[1]);
                    userDictionaryPage.put(chatId, page);
                    String section = data.split(":")[2];
                    currentMyWordsSection.put(chatId, section);
                    if ("unknown".equals(section)) {
                        editMessageWithMyWords(chatId, messageId);
                    } else {
                        editMessageWithFavoriteWords(chatId, messageId);
                    }
                } else if (data.startsWith("mywords_section:")) {
                    String section = data.split(":")[1];
                    currentMyWordsSection.put(chatId, section);
                    userDictionaryPage.put(chatId, 0);
                    if ("unknown".equals(section)) {
                        showMyWords(chatId);
                    } else {
                        showFavoriteWords(chatId);
                    }
                } else if (data.startsWith("delete_unknown:")) {
                    Long wordId = Long.parseLong(data.split(":")[1]);
                    deleteUnknownWord(chatId, wordId, messageId);
                } else if (data.startsWith("sentence_amount:")) {
                    int amount = Integer.parseInt(data.split(":")[1]);
                    handleSentenceAmountSelection(chatId, amount);
                } else if (data.startsWith("delete_favorite:")) {
                    Long wordId = Long.parseLong(data.split(":")[1]);
                    deleteFavoriteWord(chatId, wordId, messageId);
                } else if (data.equals("main_menu")) {
                    showMainMenu(chatId);
                } else if (data.equals("delete_all_unknown")) {
                    deleteAllUnknownWords(chatId, messageId);
                } else if (data.equals("delete_all_favorites")) {
                    deleteAllFavoriteWords(chatId, messageId);
                } else if (data.startsWith("dict_favorite:")) {
                    Long wordId = Long.parseLong(data.split(":")[1]);
                    addWordToFavoritesFromDictionary(chatId, wordId, messageId);
                }
                return;
            }

            Map<String, Object> message = (Map<String, Object>) update.get("message");
            if (message == null) return;

            Map<String, Object> chatMap = (Map<String, Object>) message.get("chat");
            Long chatId = ((Number) chatMap.get("id")).longValue();
            String text = (String) message.get("text");

            Map<String, Object> fromMap = (Map<String, Object>) message.get("from");
            String firstName = (String) fromMap.get("first_name");
            String lastName = (String) fromMap.get("last_name");

            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String backToMenuCmd = nativeLang.equals("ru") ? "⬅️ Назад в меню" : "⬅️ 返回菜单";
            String backToMenuFlashcardCmd = nativeLang.equals("ru") ? "Вернуться в меню" : "返回菜单";

            if (text.equals(backToMenuCmd) || text.equals(backToMenuFlashcardCmd)) {
                activeFlashcardGames.remove(chatId);
                activeSentenceGames.remove(chatId);
                showMainMenu(chatId);
                return;
            }

            if (activeFlashcardGames.containsKey(chatId)) {
                handleFlashcardGameInput(chatId, text);
                return;
            }
            if (activeSentenceGames.containsKey(chatId)) {
                handleSentenceGameInput(chatId, text);
                return;
            }

            ConversationState state = userStates.getOrDefault(chatId, ConversationState.START);

            if (text.equals(backToMenuCmd)) {
                showMainMenu(chatId);
                return;
            }

            switch (state) {
                case START -> handleStart(chatId, firstName, lastName);
                case AWAITING_NATIVE_LANG -> handleNativeLanguageSelection(chatId, text);
                case AWAITING_TARGET_LANG -> handleTargetLanguageSelection(chatId, text);
                case AWAITING_LEVEL -> handleLevelSelection(chatId, text);
                case IN_MENU -> handleMenuCommand(chatId, text);
                case IN_MY_WORDS -> handleMyWordsCommand(chatId, text);
                case IN_SENTENCE_GAME -> handleSentenceGameInput(chatId, text);
                case IN_SETTINGS -> handleSettingsCommand(chatId, text);
                case IN_DICTIONARY -> handleDictionaryCommand(chatId, text);
                case AWAITING_NEW_NATIVE_LANG -> handleNewNativeLanguageSelection(chatId, text);
                case AWAITING_NEW_TARGET_LANG -> handleNewTargetLanguageSelection(chatId, text);
                case AWAITING_NEW_LEVEL -> handleNewLevelSelection(chatId, text);
                default -> {
                    sendMessage(chatId, "Произошла ошибка. Пожалуйста, начните сначала с команды /start.");
                    userStates.put(chatId, ConversationState.START);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addWordToFavoritesFromDictionary(Long chatId, Long wordId, Integer messageId) {
        Optional<Word> wordOpt = wordRepository.findById(wordId);
        if (wordOpt.isPresent()) {
            Word word = wordOpt.get();
            addToFavoriteWords(chatId, word);
            
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            
            String successMessage = nativeLang.equals("ru") ? 
                "✅ Слово \"" + word.getWord() + "\" добавлено в избранное!" :
                "✅ 单词 \"" + word.getWord() + "\" 已添加到收藏！";

            sendMessage(chatId, successMessage);

            editMessageWithDictionary(chatId, messageId);
        } else {
            sendMessage(chatId, "Ошибка: слово не найдено.");
        }
    }

    private void showMyWords(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        List<UserWord> allUserWords = userWordRepository.findByUserChatId(chatId);
        if (allUserWords.isEmpty()) {
            String nativeLang = userOpt.get().getNativeLanguage();
            String message = nativeLang.equals("ru") ? "❌ Ты ещё не отметил ни одного слова как 'не знаю'." : "❌ 你还没有标记任何单词为\"不认识\"。";
            sendMessage(chatId, message);
            showMyWordsMenu(chatId);
            return;
        }

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allUserWords.size() / pageSize);

        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
            userDictionaryPage.put(chatId, currentPage);
        }
        if (currentPage < 0) {
            currentPage = 0;
            userDictionaryPage.put(chatId, currentPage);
        }

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allUserWords.size());
        List<UserWord> wordsOnPage = allUserWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = userOpt.get().getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("❌ *Не знаю* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на кнопку ❌ чтобы удалить слово из списка\n\n");
        } else {
            sb.append("❌ *不认识* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击 ❌ 按钮从列表中删除单词\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            UserWord uw = wordsOnPage.get(i);
            String wordLine;
            if (uw.getWord().getTranscription() != null && !uw.getWord().getTranscription().isEmpty()) {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " (" + uw.getWord().getTranscription() + ") — " + uw.getWord().getTranslation();
            } else {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " — " + uw.getWord().getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createMyWordsInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage);
        sendMessageWithInlineKeyboard(chatId, sb.toString(), keyboard);
    }

    private static class SentenceRound {
        private final List<Word> words;
        private final String correctSentence;

        public SentenceRound(List<Word> words, String correctSentence) {
            this.words = words;
            this.correctSentence = correctSentence;
        }

        public List<Word> getWords() { return words; }
        public String getCorrectSentence() { return correctSentence; }
    }

    private void showFlashcardOptions(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            return;
        }

        User user = userOpt.get();
        String nativeLang = user.getNativeLanguage();

        String currentLevel = user.getLevel();
        String levelText = nativeLang.equals("ru") ? 
            "📊 Указанный вами уровень знания языка: *" + currentLevel + "*" :
            "📊 您指定的语言知识水平: *" + currentLevel + "*";

        String text = levelText + "\n\n" +
            (nativeLang.equals("ru") ? 
                "⚙️ *Настройки игры 'Карточки':*\n\n" +
                "Выбери количество слов и источник:" :
                "⚙️ *\"单词卡片\"游戏设置:*\n\n" +
                "选择单词数量和来源：");

        List<List<String>> buttons;
        if (nativeLang.equals("ru")) {
            buttons = List.of(
                List.of("10 слов", "20 слов", "30 слов"),
                List.of("45 слов", "60 слов", "90 слов"),
                List.of("Все слова", "Только мои слова"),
                List.of("🎛️ Настройки уровня"),
                List.of("⬅️ Назад в меню")
            );
        } else {
            buttons = List.of(
                List.of("10 个词", "20 个词", "30 个词"),
                List.of("45 个词", "60 个词", "90 个词"),
                List.of("全部单词", "仅我的单词"),
                List.of("🎛️ 级别设置"),
                List.of("⬅️ 返回菜单")
            );
        }

        sendMessageWithButtons(chatId, text, buttons);
    }

    private void showFlashcardLevelSettings(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String text = nativeLang.equals("ru") ? 
            "🎛️ *Настройка уровня слов для игры:*\n\n" +
            "Выбери уровень слов, которые будут использоваться в игре:" :
            "🎛️ *游戏单词级别设置:*\n\n" +
            "选择游戏中使用的单词级别：";

        List<List<String>> buttons;
        if (nativeLang.equals("ru")) {
            buttons = List.of(
                List.of("A1", "A2", "B1"),
                List.of("B2", "C1", "C2"),
                List.of("📊 Текущий уровень"),
                List.of("⬅️ Назад к игре")
            );
        } else {
            buttons = List.of(
                List.of("A1", "A2", "B1"),
                List.of("B2", "C1", "C2"),
                List.of("📊 当前级别"),
                List.of("⬅️ 返回游戏")
            );
        }

        sendMessageWithButtons(chatId, text, buttons);
    }

    private void editMessageWithFavoriteWords(Long chatId, Integer messageId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) return;

        List<UserFavoriteWord> allUserFavorites = userFavoriteWordRepository.findByUserChatId(chatId);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allUserFavorites.size() / pageSize);

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allUserFavorites.size());
        List<UserFavoriteWord> wordsOnPage = allUserFavorites.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = userOpt.get().getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("⭐ *Избранное* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на кнопку ❌ чтобы удалить слово из избранного\n\n");
        } else {
            sb.append("⭐ *收藏* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击 ❌ 按钮从收藏中删除单词\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            UserFavoriteWord uw = wordsOnPage.get(i);
            String wordLine;
            if (uw.getWord().getTranscription() != null && !uw.getWord().getTranscription().isEmpty()) {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " (" + uw.getWord().getTranscription() + ") — " + uw.getWord().getTranslation();
            } else {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " — " + uw.getWord().getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createFavoriteWordsInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage);
        editMessageText(chatId, messageId, sb.toString(), keyboard);
    }

    private void addToFavoriteWords(Long chatId, Word word) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            Optional<UserFavoriteWord> existingFav = userFavoriteWordRepository.findByUserChatIdAndWordId(chatId, word.getId());
            if (existingFav.isEmpty()) {
                UserFavoriteWord fav = new UserFavoriteWord();
                fav.setUserChatId(chatId);
                fav.setWord(word);
                userFavoriteWordRepository.save(fav);
            }
        }
    }    

    private void editMessageWithMyWords(Long chatId, Integer messageId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) return;

        List<UserWord> allUserWords = userWordRepository.findByUserChatId(chatId);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allUserWords.size() / pageSize);

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allUserWords.size());
        List<UserWord> wordsOnPage = allUserWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = userOpt.get().getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("❌ *Не знаю* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на кнопку ❌ чтобы удалить слово из списка\n\n");
        } else {
            sb.append("❌ *不认识* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击 ❌ 按钮从列表中删除单词\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            UserWord uw = wordsOnPage.get(i);
            String wordLine;
            if (uw.getWord().getTranscription() != null && !uw.getWord().getTranscription().isEmpty()) {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " (" + uw.getWord().getTranscription() + ") — " + uw.getWord().getTranslation();
            } else {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " — " + uw.getWord().getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createMyWordsInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage);
        editMessageText(chatId, messageId, sb.toString(), keyboard);
    }

    private void handleStart(Long chatId, String firstName, String lastName) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        User user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);
            String nativeLang = user.getNativeLanguage();
            String welcomeBackText = nativeLang.equals("ru") ? "С возвращением, " : "欢迎回来，";
            sendMessage(chatId, welcomeBackText + firstName + "! 👋");
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
        } else {
            user = new User();
            user.setChatId(chatId);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setRegisteredAt(LocalDateTime.now());
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);

            String welcomeText = "你好，" + firstName + "! 👋\n" +
                    "我是你学习俄语和汉语的助手!\n" +
                    "首先，选择您的母语。:\n" +
                    "Привет, " + firstName + "! 👋\n" +
                    "Я твой помощник в изучении русского и китайского языков!\n" +
                    "Для начала выбери свой родной язык:";
            List<List<String>> languageButtons = List.of(
                    List.of("🇷🇺 Русский", "🇨🇳 中文")
            );
            sendMessageWithButtons(chatId, welcomeText, languageButtons);
            userStates.put(chatId, ConversationState.AWAITING_NATIVE_LANG);
        }
    }

    private void handleNativeLanguageSelection(Long chatId, String selectedLanguage) {
        String nativeLangCode;
        String targetLangText;
        List<List<String>> targetLangButtons;

        if (selectedLanguage.equals("🇷🇺 Русский")) {
            nativeLangCode = "ru";
            targetLangText = "Отлично! Теперь выбери язык, который ты хочешь изучать:";
            targetLangButtons = List.of(List.of("🇨🇳 中文"));
        } else if (selectedLanguage.equals("🇨🇳 中文")) {
            nativeLangCode = "zh";
            targetLangText = "很好！现在选择你想学习的语言：";
            targetLangButtons = List.of(List.of("🇷🇺 Русский"));
        } else {
            sendMessage(chatId, "Пожалуйста, выбери язык из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setNativeLanguage(nativeLangCode);
            userRepository.save(user);

            sendMessageWithButtons(chatId, targetLangText, targetLangButtons);
            userStates.put(chatId, ConversationState.AWAITING_TARGET_LANG);
        } else {
            sendMessage(chatId, nativeLangCode.equals("ru") ? "Ошибка. Пожалуйста, начни сначала с /start." : "错误。请从 /start 重新开始。");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void handleTargetLanguageSelection(Long chatId, String selectedLanguage) {
        String targetLangCode;
        String levelText;
        List<List<String>> levelButtons;

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
            return;
        }
        User user = userOpt.get();
        String nativeLang = user.getNativeLanguage();

        if (nativeLang.equals("ru") && selectedLanguage.equals("🇨🇳 中文")) {
            targetLangCode = "zh";
        } else if (nativeLang.equals("zh") && selectedLanguage.equals("🇷🇺 Русский")) {
            targetLangCode = "ru";
        } else {
            String errorMessage = nativeLang.equals("ru") ? "Пожалуйста, выбери язык из предложенных вариантов." : "请选择提供的选项之一。";
            sendMessage(chatId, errorMessage);
            String targetLangText = nativeLang.equals("ru") ? "Отлично! Теперь выбери язык, который ты хочешь изучать:" : "很好！现在选择你想学习的语言：";
            List<List<String>> targetLangButtons = nativeLang.equals("ru") ? List.of(List.of("🇨🇳 中文")) : List.of(List.of("🇷🇺 Русский"));
            sendMessageWithButtons(chatId, targetLangText, targetLangButtons);
            return;
        }

        user.setTargetLanguage(targetLangCode);
        userRepository.save(user);

        if (nativeLang.equals("ru")) {
            levelText = "Выбери свой уровень знаний:";
            levelButtons = List.of(
                    List.of("A1", "A2"),
                    List.of("B1", "B2"),
                    List.of("C1", "C2")
            );
        } else {
            levelText = "选择你的知识水平：";
            levelButtons = List.of(
                    List.of("A1", "A2"),
                    List.of("B1", "B2"),
                    List.of("C1", "C2")
            );
        }

        sendMessageWithButtons(chatId, levelText, levelButtons);
        userStates.put(chatId, ConversationState.AWAITING_LEVEL);
    }

    private void handleLevelSelection(Long chatId, String selectedLevel) {
        if (!List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(selectedLevel)) {
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String errorMessage = nativeLang.equals("ru") ? "Пожалуйста, выбери уровень из предложенных вариантов." : "请选择提供的级别之一。";
            sendMessage(chatId, errorMessage);
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLevel(selectedLevel);
            userRepository.save(user);

            String nativeLang = user.getNativeLanguage();
            String targetLangName = ("ru".equals(user.getTargetLanguage()) ? (nativeLang.equals("ru") ? "Русский" : "俄语") : (nativeLang.equals("ru") ? "Китайский" : "中文"));
            String confirmationText;
            if (nativeLang.equals("ru")) {
                confirmationText = "Отлично! Ты выбрал уровень *" + selectedLevel + "* для изучения языка *" + targetLangName + "*.";
            } else {
                confirmationText = "很好！你选择了 *" + selectedLevel + "* 级别来学习 *" + targetLangName + "*。";
            }

            sendMessage(chatId, confirmationText);
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
        } else {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void showMainMenu(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String menuText;
        List<List<String>> menuButtons;

        if (nativeLang.equals("ru")) {
            menuText = "🎯 *Главное меню*";
            menuButtons = List.of(
                    List.of("🎮 Игры"),
                    List.of("📘 Словарь", "🔁 Мои слова"),
                    List.of("⚙️ Настройки")
            );
        } else {
            menuText = "🎯 *主菜单*";
            menuButtons = List.of(
                    List.of("🎮 游戏"),
                    List.of("📘 词典", "🔁 我的单词"),
                    List.of("⚙️ 设置")
            );
        }

        sendMessageWithButtons(chatId, menuText, menuButtons);
        userStates.put(chatId, ConversationState.IN_MENU);
    }

    private void handleMenuCommand(Long chatId, String command) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String gamesCmd = nativeLang.equals("ru") ? "🎮 Игры" : "🎮 游戏";
        String dictCmd = nativeLang.equals("ru") ? "📘 Словарь" : "📘 词典";
        String myWordsCmd = nativeLang.equals("ru") ? "🔁 Мои слова" : "🔁 我的单词";
        String settingsCmd = nativeLang.equals("ru") ? "⚙️ Настройки" : "⚙️ 设置";
        String flashcardGameCmd = nativeLang.equals("ru") ? "Flash card (Карточки)" : "Flash card (单词卡片)";
        String sentenceGameCmd = nativeLang.equals("ru") ? "Sentence (Составить предложение)" : "Sentence (造句)";

        if (command.equals(gamesCmd)) {
            showGamesMenu(chatId);
        } else if (command.equals(dictCmd)) {
            userDictionaryPage.put(chatId, 0);
            showDictionary(chatId);
        } else if (command.equals(myWordsCmd)) {
            showMyWordsMenu(chatId);
        } else if (command.equals(settingsCmd)) {
            showSettings(chatId);
        } else if (command.equals("/start")) {
            if(userOpt.isPresent()) {
                showMainMenu(chatId);
            } else {
                handleStart(chatId, "User", "");
            }
        } 
        else if (command.equals(nativeLang.equals("ru") ? "❌ Не знаю" : "❌ 不认识")) {
            currentMyWordsSection.put(chatId, "unknown");
            userDictionaryPage.put(chatId, 0);
            showMyWords(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "⭐ Избранное" : "⭐ 收藏")) {
            currentMyWordsSection.put(chatId, "favorites");
            userDictionaryPage.put(chatId, 0);
            showFavoriteWords(chatId);
        } else if (command.equals(flashcardGameCmd)) {
            showFlashcardOptions(chatId);
        } else if (command.matches("^(10|20|30|45|60|90) (слов|个词)$")) {
            int amount = Integer.parseInt(command.split(" ")[0]);
            startFlashcardGame(chatId, amount, false);
        } else if (command.contains("Все слова") || command.contains("全部单词")) {
            startFlashcardGame(chatId, null, false);
        } else if (command.contains("Только мои слова") || command.contains("仅我的单词")) {
            startFlashcardGame(chatId, null, true);
        } else if (command.equals(sentenceGameCmd)) {
            startSentenceGame(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            if (currentPage > 0) {
                userDictionaryPage.put(chatId, currentPage - 1);
            }
            showDictionary(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            int totalPages = (int) Math.ceil((double) wordRepository.findByLevelAndLang(
                userOpt.get().getLevel(), userOpt.get().getTargetLanguage()).size() / 30.0);
            if (currentPage < totalPages - 1) {
                userDictionaryPage.put(chatId, currentPage + 1);
            }
            showDictionary(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单")) {
            showMainMenu(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "🎛️ Настройки уровня" : "🎛️ 级别设置")) {
            showFlashcardLevelSettings(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "📊 Текущий уровень" : "📊 当前级别")) {
            if (userOpt.isPresent()) {
                String currentLevel = userOpt.get().getLevel();
                String message = nativeLang.equals("ru") ? 
                    "📊 Ваш текущий уровень: *" + currentLevel + "*" :
                    "📊 您当前的级别: *" + currentLevel + "*";
                sendMessage(chatId, message);
            }
            showFlashcardLevelSettings(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "⬅️ Назад к игре" : "⬅️ 返回游戏")) {
            showFlashcardOptions(chatId);
        } else if (Arrays.asList("A1", "A2", "B1", "B2", "C1", "C2").contains(command)) {
            userDictionaryLevel.put(chatId, command);
            String message = nativeLang.equals("ru") ? 
                "✅ Уровень слов для игры установлен: *" + command + "*" :
                "✅ 游戏单词级别已设置: *" + command + "*";
            sendMessage(chatId, message);
            showFlashcardOptions(chatId);
        } else if (command.equals(dictCmd)) {
            showDictionaryLevelSelection(chatId);
        } else if (Arrays.asList("A1", "A2", "B1", "B2", "C1", "C2").contains(command)) {
            showDictionaryWithLevel(chatId, command);
        } else if (command.equals(nativeLang.equals("ru") ? "📊 Все уровни" : "📊 所有级别")) {
            showDictionaryWithLevel(chatId, "ALL");
        } else {
            String message = nativeLang.equals("ru") ? "Неизвестная команда. Пожалуйста, используй меню." : "未知命令。请使用菜单。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
        }
    }

    private void showGamesMenu(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String gamesText;
        List<List<String>> gameButtons;

        if (nativeLang.equals("ru")) {
            gamesText = "🎲 *Выбери игру:*";
            gameButtons = List.of(
                    List.of("Flash card (Карточки)", "Sentence (Составить предложение)"),
                    List.of("⬅️ Назад в меню")
            );
        } else {
            gamesText = "🎲 *选择游戏:*";
            gameButtons = List.of(
                    List.of("Flash card (单词卡片)", "Sentence (造句)"),
                    List.of("⬅️ 返回菜单")
            );
        }

        sendMessageWithButtons(chatId, gamesText, gameButtons);
    }

    private void startFlashcardGame(Long chatId, Integer amount, boolean useMyWordsOnly) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        User user = userOpt.get();
        List<Word> words;

        String gameLevel = userDictionaryLevel.getOrDefault(chatId, user.getLevel());

        if (useMyWordsOnly) {
            List<UserWord> userWords = userWordRepository.findByUserChatId(chatId);
            words = userWords.stream().map(UserWord::getWord).collect(Collectors.toList());
            
            if (words.isEmpty()) {
                String nativeLang = user.getNativeLanguage();
                String message = nativeLang.equals("ru") ? "😔 В твоём списке 'Не знаю' пока нет слов." : "😔 你的'不认识'列表中还没有单词。";
                sendMessage(chatId, message);
                showMainMenu(chatId);
                return;
            }
        } else {
            words = wordRepository.findByLevelAndLang(gameLevel, user.getTargetLanguage());
        }

        if (words.isEmpty()) {
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? 
                "😔 Нет слов уровня " + gameLevel + " для игры." : 
                "😔 没有 " + gameLevel + " 级别的单词可游戏。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        if (amount != null && amount < words.size()) {
            Collections.shuffle(words);
            words = words.subList(0, amount);
        }

        FlashcardGameSession session = new FlashcardGameSession(chatId, "flashcard", words, 0, useMyWordsOnly, gameLevel);
        activeFlashcardGames.put(chatId, session);

        String nativeLang = user.getNativeLanguage();
        String levelInfo = nativeLang.equals("ru") ? 
            "🎮 Начата игра с уровнем слов: *" + gameLevel + "*" :
            "🎮 开始游戏，单词级别: *" + gameLevel + "*";
        sendMessage(chatId, levelInfo);
        
        sendFlashcard(chatId, session);
    }

    private void sendFlashcard(Long chatId, FlashcardGameSession session) {
        int index = session.getCurrentIndex();
        List<Word> words = session.getWords();

        if (index >= words.size()) {
            finishFlashcardGame(chatId, session);
            return;
        }

        Word currentWord = words.get(index);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String wordDisplay;
        if (currentWord.getTranscription() != null && !currentWord.getTranscription().isEmpty()) {
            wordDisplay = currentWord.getWord() + " (" + currentWord.getTranscription() + ")";
        } else {
            wordDisplay = currentWord.getWord();
        }

        String question;
        String instruction;
        if (nativeLang.equals("ru")) {
            question = "🔤 *Переведи слово:*\n\n" + wordDisplay;
            instruction = "\n\n(Напиши перевод или выбери действие)";
        } else {
            question = "🔤 *翻译单词:*\n\n" + wordDisplay;
            instruction = "\n\n(写下翻译或选择操作)";
        }

        String dontKnowButton = nativeLang.equals("ru") ? "❌ Не знаю" : "❌ 不认识";
        String addToFavoritesButton = nativeLang.equals("ru") ? "⭐ В избранное" : "⭐ 添加到收藏";
        String backToMenuButton = nativeLang.equals("ru") ? "Вернуться в меню" : "返回菜单";

        List<List<String>> buttons = List.of(
            List.of(dontKnowButton, addToFavoritesButton),
            List.of(backToMenuButton)
        );

        sendMessageWithButtons(chatId, question + instruction, buttons);
    }

    private void handleFlashcardGameInput(Long chatId, String userAnswer) {
        FlashcardGameSession session = activeFlashcardGames.get(chatId);
        if (session == null) {
            sendMessage(chatId, "Игра не найдена. Вернись в меню.");
            showMainMenu(chatId);
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
        String backToMenuFlashcardCmd = nativeLang.equals("ru") ? "Вернуться в меню" : "返回菜单";
        String addToFavoritesButton = nativeLang.equals("ru") ? "⭐ В избранное" : "⭐ 添加到收藏";
        String dontKnowButton = nativeLang.equals("ru") ? "❌ Не знаю" : "❌ 不认识";

        if (userAnswer.equals(backToMenuFlashcardCmd)) {
            activeFlashcardGames.remove(chatId);
            showMainMenu(chatId);
            return;
        }

        if (userAnswer.equals(addToFavoritesButton)) {
            List<Word> words = session.getWords();
            int index = session.getCurrentIndex();
            Word currentWord = words.get(index);
            
            addToFavoriteWords(chatId, currentWord);
            
            String response = nativeLang.equals("ru") ? 
                "⭐ Слово \"" + currentWord.getWord() + "\" добавлено в избранное!\n" +
                "🔤 Перевод: *" + currentWord.getTranslation() + "*" : 
                "⭐ 单词 \"" + currentWord.getWord() + "\" 已添加到收藏！\n" +
                "🔤 翻译: *" + currentWord.getTranslation() + "*";
            sendMessage(chatId, response);

            session.setCurrentIndex(index + 1);
            activeFlashcardGames.put(chatId, session);
            
            if (session.getCurrentIndex() >= words.size()) {
                finishFlashcardGame(chatId, session);
            } else {
                sendFlashcard(chatId, session);
            }
            return;
        }

        if (userAnswer.equals(dontKnowButton)) {
            List<Word> words = session.getWords();
            int index = session.getCurrentIndex();
            Word currentWord = words.get(index);
            
            session.incrementDontKnowCount();
            String correctAnswer = currentWord.getTranslation();
            String response = nativeLang.equals("ru") ? 
                "🔹 Правильный перевод: *" + correctAnswer + "*" : 
                "🔹 正确翻译: *" + correctAnswer + "*";
            
            sendMessage(chatId, response);
            addToMyWords(chatId, currentWord);

            session.setCurrentIndex(index + 1);
            activeFlashcardGames.put(chatId, session);
            
            if (session.getCurrentIndex() >= words.size()) {
                finishFlashcardGame(chatId, session);
            } else {
                sendFlashcard(chatId, session);
            }
            return;
        }

        List<Word> words = session.getWords();
        int index = session.getCurrentIndex();
        Word currentWord = words.get(index);
        String correctAnswer = currentWord.getTranslation();

        String response;
        boolean isCorrect = false;

        String[] correctAnswers = correctAnswer.split(",");
        for (String correct : correctAnswers) {
            if (userAnswer.trim().equalsIgnoreCase(correct.trim())) {
                isCorrect = true;
                break;
            }
        }

        if (isCorrect) {
            session.incrementCorrectCount();
            response = nativeLang.equals("ru") ? "✅ Правильно!" : "✅ 正确！";

            if (session.isUseMyWordsOnly()) {
                removeFromUnknownWords(chatId, currentWord);
            }
        } else {
            session.incrementDontKnowCount();
            response = nativeLang.equals("ru") ? 
                "❌ Неправильно. \nПравильный перевод: *" + correctAnswer + "*" : 
                "❌ 错误。\n 正确翻译: *" + correctAnswer + "*";
            addToMyWords(chatId, currentWord);
        }

        sendMessage(chatId, response);

        session.setCurrentIndex(index + 1);
        activeFlashcardGames.put(chatId, session);

        if (session.getCurrentIndex() >= words.size()) {
            finishFlashcardGame(chatId, session);
        } else {
            sendFlashcard(chatId, session);
        }
    }

    private void removeFromUnknownWords(Long chatId, Word word) {
        Optional<UserWord> userWordOpt = userWordRepository.findByUserChatIdAndWordId(chatId, word.getId());
        if (userWordOpt.isPresent()) {
            userWordRepository.delete(userWordOpt.get());
        }
    }

    private void finishFlashcardGame(Long chatId, FlashcardGameSession session) {
        activeFlashcardGames.remove(chatId);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        long timeSpent = (System.currentTimeMillis() - session.getStartTime()) / 1000;
        int correct = session.getCorrectCount();
        int dontKnow = session.getDontKnowCount();
        int total = session.getWords().size();
        double percentage = total > 0 ? (correct * 100.0) / total : 0;

        String stats;
        if (nativeLang.equals("ru")) {
            stats = "📊 *Статистика игры:*\n" + 
                    "Правильно: " + correct + "/" + total + " (" + String.format("%.1f", percentage) + "%)\n" + 
                    "Не знаю: " + dontKnow + "\n" + 
                    "Время игры: " + timeSpent + " секунд";
        } else {
            stats = "📊 *游戏统计:*\n" + 
                    "正确: " + correct + "/" + total + " (" + String.format("%.1f", percentage) + "%)\n" + 
                    "不认识: " + dontKnow + "\n" + 
                    "游戏时间: " + timeSpent + " 秒";
        }

        sendMessage(chatId, stats);

        if (percentage >= 95.0) {
            String testSuggestion = nativeLang.equals("ru") ? 
                "\n🎉 *Отличный результат!*\n" +
                "Вы набрали более 95% верных ответов!\n" +
                "Попробуйте пройти онлайн тестирование на знание уровня *" + userOpt.get().getLevel() + "*.\n" +
                "Ссылка на тестирование: https://your-testing-platform.com/level-" + userOpt.get().getLevel().toLowerCase() :
                
                "\n🎉 *优秀成绩!*\n" +
                "您获得了超过95%的正确答案！\n" +
                "尝试参加 *" + userOpt.get().getLevel() + "* 级别的在线测试。\n" +
                "测试链接: https://your-testing-platform.com/level-" + userOpt.get().getLevel().toLowerCase();
            
            sendMessage(chatId, testSuggestion);
        }

        String finishMessage = nativeLang.equals("ru") ? 
            "🎉 Игра 'Карточки' окончена! Хорошая работа!" :
            "🎉 \"单词卡片\"游戏结束！做得好！";

        sendMessage(chatId, finishMessage);
        showMainMenu(chatId);
    }

    private void startSentenceGame(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        User user = userOpt.get();

        List<Sentence> sentences = sentenceRepository.findByLevelUpToAndLanguage(user.getLevel(), user.getTargetLanguage());

        if (sentences.isEmpty()) {
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? 
                "😔 Нет предложений для твоего уровня. Попробуй другой уровень или язык." : 
                "😔 没有适合你级别的句子。尝试其他级别或语言。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        int sentenceAmount = user.getSentenceGameAmount() != null ? user.getSentenceGameAmount() : 5;

        SentenceGameSession session = new SentenceGameSession(chatId, sentences, sentenceAmount, user.getTargetLanguage());
        activeSentenceGames.put(chatId, session);

        sendNextSentence(chatId, session);
    }

    private void finishSentenceGame(Long chatId, SentenceGameSession session) {
        activeSentenceGames.remove(chatId);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        long timeSpent = (System.currentTimeMillis() - session.getStartTime()) / 1000;
        int correct = session.getCorrectCount();
        int incorrect = session.getIncorrectCount();
        int total = session.getSentences().size();

        String stats;
        if (nativeLang.equals("ru")) {
            stats = "📊 *Статистика игры 'Составить предложение':*\n" + 
                    "Правильно: " + correct + "/" + total + "\n" + 
                    "Неправильно: " + incorrect + "\n" + 
                    "Время игры: " + timeSpent + " секунд";
        } else {
            stats = "📊 *'造句'游戏统计:*\n" + 
                    "正确: " + correct + "/" + total + "\n" + 
                    "错误: " + incorrect + "\n" + 
                    "游戏时间: " + timeSpent + " 秒";
        }

        sendMessage(chatId, stats);

        String finishMessage;
        if (nativeLang.equals("ru")) {
            finishMessage = "🎉 Игра 'Составить предложение' окончена! Хорошая работа!";
        } else {
            finishMessage = "🎉 '造句'游戏结束！做得好！";
        }

        sendMessage(chatId, finishMessage);
        showMainMenu(chatId);
    }

    private void sendNextSentence(Long chatId, SentenceGameSession session) {
        if (session.isFinished()) {
            finishSentenceGame(chatId, session);
            return;
        }

        Sentence currentSentence = session.getCurrentSentence();

        List<String> words = Arrays.asList(currentSentence.getWords().split("\\s*,\\s*"));
        Collections.shuffle(words);

        StringBuilder sb = new StringBuilder();
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        if (nativeLang.equals("ru")) {
            sb.append("✍️ *Составь предложение из этих слов* (").append(session.getCurrentRound() + 1).append("/").append(session.getSentences().size()).append("):\n\n");
        } else {
            sb.append("✍️ *用这些词造句* (").append(session.getCurrentRound() + 1).append("/").append(session.getSentences().size()).append("):\n\n");
        }

        sb.append(String.join(", ", words));

        if (nativeLang.equals("ru")) {
            sb.append("\n\nНапиши предложение в чат.");
        } else {
            sb.append("\n\n在聊天中写下句子。");
        }

        sendMessage(chatId, sb.toString());
    }

    private void showSentenceOptions(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String text;
        List<List<String>> buttons;

        if (nativeLang.equals("ru")) {
            text = "⚙️ *Настройки игры 'Составить предложение':*\n\n" +
                    "Выбери количество предложений:";
            buttons = List.of(
                    List.of("5 предложений", "10 предложений"),
                    List.of("15 предложений", "20 предложений"),
                    List.of("⬅️ Назад в меню")
            );
        } else {
            text = "⚙️ *'造句'游戏设置:*\n\n" +
                    "选择句子数量：";
            buttons = List.of(
                    List.of("5 个句子", "10 个句子"),
                    List.of("15 个句子", "20 个句子"),
                    List.of("⬅️ 返回菜单")
            );
        }

        sendMessageWithButtons(chatId, text, buttons);
    }

    private static String createSimpleSentence(List<Word> words, String lang) {
        if ("ru".equalsIgnoreCase(lang) && words.size() >= 3) {
            return words.get(0).getWord() + " " + words.get(1).getWord() + " " + words.get(2).getWord() + ".";
        } else if ("zh".equalsIgnoreCase(lang) && words.size() >= 3) {
            return words.get(0).getWord() + words.get(1).getWord() + words.get(2).getWord() + "。";
        }
        return words.stream().map(Word::getWord).collect(Collectors.joining(" ")) + ".";
    }

    private void handleSentenceGameInput(Long chatId, String userSentence) {
        SentenceGameSession session = activeSentenceGames.get(chatId);
        if (session == null) {
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String message = nativeLang.equals("ru") ? "Неизвестная команда. Пожалуйста, используй меню." : "未知命令。请使用菜单。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
            return;
        }

        Sentence currentSentence = session.getCurrentSentence();
        String correctSentence = currentSentence.getCorrectSentence();

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
        
        String response;
        if (userSentence.trim().equalsIgnoreCase(correctSentence.trim())) {
            session.incrementCorrectCount();
            if (nativeLang.equals("ru")) {
                response = "✅ Правильно! Отличное предложение!";
            } else {
                response = "✅ 正确！好句子！";
            }
        } else {
            session.incrementIncorrectCount();
            if (nativeLang.equals("ru")) {
                response = "❌ Неправильно.\nПравильный вариант: *" + correctSentence + "*";
            } else {
                response = "❌ 错误。\n正确答案: *" + correctSentence + "*";
            }
        }

        sendMessage(chatId, response);

        session.setCurrentRound(session.getCurrentRound() + 1);

        if (session.isFinished()) {
            finishSentenceGame(chatId, session);
        } else {
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    sendNextSentence(chatId, session);
                } catch (InterruptedException e) {
                    sendNextSentence(chatId, session);
                }
            }).start();
        }
    }

    private void showDictionaryLevelSelection(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String text = nativeLang.equals("ru") ? 
            "📚 *Выбор уровня словаря:*\n\n" +
            "Выбери уровень слов для просмотра:" :
            "📚 *词典级别选择:*\n\n" +
            "选择要查看的单词级别：";

        List<List<String>> buttons;
        if (nativeLang.equals("ru")) {
            buttons = List.of(
                List.of("A1", "A2", "B1"),
                List.of("B2", "C1", "C2"),
                List.of("📊 Все уровни"),
                List.of("⬅️ Назад в меню")
            );
        } else {
            buttons = List.of(
                List.of("A1", "A2", "B1"),
                List.of("B2", "C1", "C2"),
                List.of("📊 所有级别"),
                List.of("⬅️ 返回菜单")
            );
        }

        sendMessageWithButtons(chatId, text, buttons);
    }

    private void showDictionaryWithLevel(Long chatId, String level) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        User user = userOpt.get();
        String targetLang = user.getTargetLanguage();

        List<Word> allWords;
        if ("ALL".equals(level)) {
            allWords = wordRepository.findByLang(targetLang);
        } else {
            allWords = wordRepository.findByLevelAndLang(level, targetLang);
        }

        if (allWords.isEmpty()) {
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? 
                "😔 Нет слов уровня " + level + " в словаре." : 
                "😔 词典中没有 " + level + " 级别的单词。";
            sendMessage(chatId, message);
            showDictionaryLevelSelection(chatId);
            return;
        }

        userDictionaryPage.put(chatId, 0);
        userDictionaryLevel.put(chatId, level);
        
        showDictionaryPage(chatId, level, allWords);
    }

    private void showDictionaryPage(Long chatId, String level, List<Word> allWords) {
        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allWords.size() / pageSize);

        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
            userDictionaryPage.put(chatId, currentPage);
        }
        if (currentPage < 0) {
            currentPage = 0;
            userDictionaryPage.put(chatId, currentPage);
        }

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allWords.size());
        List<Word> wordsOnPage = allWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.get().getNativeLanguage();

        String levelDisplay = "ALL".equals(level) ? 
            (nativeLang.equals("ru") ? "Все уровни" : "所有级别") : level;

        if (nativeLang.equals("ru")) {
            sb.append("📖 Словарь - Уровень *").append(levelDisplay).append("* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на звезду ⭐ чтобы добавить слово в избранное\n\n");
        } else {
            sb.append("📖 词典 - 级别 *").append(levelDisplay).append("* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击星星 ⭐ 将单词添加到收藏\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            Word w = wordsOnPage.get(i);
            int globalIndex = currentPage * pageSize + i + 1;
            String wordLine;
            if (w.getTranscription() != null && !w.getTranscription().isEmpty()) {
                wordLine = globalIndex + ". " + w.getWord() + " (" + w.getTranscription() + ") — " + w.getTranslation();
            } else {
                wordLine = globalIndex + ". " + w.getWord() + " — " + w.getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createDictionaryInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage, level);
        sendMessageWithInlineKeyboard(chatId, sb.toString(), keyboard);
    }

    private void showDictionary(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }
        User user = userOpt.get();
        String level = user.getLevel();
        String targetLang = user.getTargetLanguage();

        List<Word> allWords = wordRepository.findByLevelAndLang(level, targetLang);

        if (allWords.isEmpty()) {
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? "😔 Нет слов для этого уровня." : "😔 此级别没有单词。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allWords.size() / pageSize);

        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
            userDictionaryPage.put(chatId, currentPage);
        }
        if (currentPage < 0) {
            currentPage = 0;
            userDictionaryPage.put(chatId, currentPage);
        }

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allWords.size());
        List<Word> wordsOnPage = allWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = user.getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("📖 Словарь (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на звезду ⭐ чтобы добавить слово в избранное\n\n");
        } else {
            sb.append("📖 词典 (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击星星 ⭐ 将单词添加到收藏\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            Word w = wordsOnPage.get(i);
            int globalIndex = currentPage * pageSize + i + 1;
            String wordLine;
            if (w.getTranscription() != null && !w.getTranscription().isEmpty()) {
                wordLine = globalIndex + ". " + w.getWord() + " (" + w.getTranscription() + ") — " + w.getTranslation();
            } else {
                wordLine = globalIndex + ". " + w.getWord() + " — " + w.getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createDictionaryInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage, level);
        sendMessageWithInlineKeyboard(chatId, sb.toString(), keyboard);
    }

    private InlineKeyboardMarkup createDictionaryInlineKeyboard(Long chatId, int currentPage, int totalPages, String nativeLang, List<Word> wordsOnPage, String level) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        int wordsPerRow = 5;
        for (int i = 0; i < wordsOnPage.size(); i += wordsPerRow) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = i; j < Math.min(i + wordsPerRow, wordsOnPage.size()); j++) {
                InlineKeyboardButton favoriteButton = new InlineKeyboardButton();
                favoriteButton.setText("⭐ " + (currentPage * 30 + j + 1));
                favoriteButton.setCallbackData("dict_favorite:" + wordsOnPage.get(j).getId());
                row.add(favoriteButton);
            }
            rows.add(row);
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (currentPage > 0) {
            InlineKeyboardButton backButton = new InlineKeyboardButton();
            backButton.setText(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
            backButton.setCallbackData("dict_prev:" + (currentPage - 1) + ":" + level);
            navRow.add(backButton);
        }
        if (currentPage < totalPages - 1) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
            nextButton.setCallbackData("dict_next:" + (currentPage + 1) + ":" + level);
            navRow.add(nextButton);
        }

        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        List<InlineKeyboardButton> menuRow = new ArrayList<>();
        InlineKeyboardButton menuButton = new InlineKeyboardButton();
        menuButton.setText(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单");
        menuButton.setCallbackData("main_menu");
        menuRow.add(menuButton);
        rows.add(menuRow);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private void sendMessageWithInlineKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");
            request.put("reply_markup", keyboard);

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForObject(SEND_MESSAGE_URL + botToken + "/sendMessage", request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private InlineKeyboardMarkup createDictionaryInlineKeyboard(Long chatId, int currentPage, int totalPages, String nativeLang) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (currentPage > 0) {
            InlineKeyboardButton backButton = new InlineKeyboardButton();
            backButton.setText(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
            backButton.setCallbackData("dict_prev:" + (currentPage - 1));
            navRow.add(backButton);
        }
        if (currentPage < totalPages - 1) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
            nextButton.setCallbackData("dict_next:" + (currentPage + 1));
            navRow.add(nextButton);
        }

        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        List<InlineKeyboardButton> menuRow = new ArrayList<>();
        InlineKeyboardButton menuButton = new InlineKeyboardButton();
        menuButton.setText(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单");
        menuButton.setCallbackData("main_menu");
        menuRow.add(menuButton);
        rows.add(menuRow);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private void editMessageWithDictionary(Long chatId, Integer messageId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) return;

        User user = userOpt.get();
        String level = userDictionaryLevel.getOrDefault(chatId, user.getLevel());
        String targetLang = user.getTargetLanguage();

        List<Word> allWords;
        if ("ALL".equals(level)) {
            allWords = wordRepository.findByLang(targetLang);
        } else {
            allWords = wordRepository.findByLevelAndLang(level, targetLang);
        }

        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allWords.size() / pageSize);

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allWords.size());
        List<Word> wordsOnPage = allWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = user.getNativeLanguage();

        String levelDisplay = "ALL".equals(level) ? 
            (nativeLang.equals("ru") ? "Все уровни" : "所有级别") : level;

        if (nativeLang.equals("ru")) {
            sb.append("📖 Словарь - Уровень *").append(levelDisplay).append("* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на звезду ⭐ чтобы добавить слово в избранное\n\n");
        } else {
            sb.append("📖 词典 - 级别 *").append(levelDisplay).append("* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击星星 ⭐ 将单词添加到收藏\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            Word w = wordsOnPage.get(i);
            int globalIndex = currentPage * pageSize + i + 1;
            String wordLine;
            if (w.getTranscription() != null && !w.getTranscription().isEmpty()) {
                wordLine = globalIndex + ". " + w.getWord() + " (" + w.getTranscription() + ") — " + w.getTranslation();
            } else {
                wordLine = globalIndex + ". " + w.getWord() + " — " + w.getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createDictionaryInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage, level);
        editMessageText(chatId, messageId, sb.toString(), keyboard);
    }

    private void editMessageText(Long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("message_id", messageId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");
            request.put("reply_markup", keyboard);

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForObject(SEND_MESSAGE_URL + botToken + "/editMessageText", request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleDictionaryCommand(Long chatId, String text) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
            return;
        }
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        if (text.equals(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            if (currentPage > 0) {
                userDictionaryPage.put(chatId, currentPage - 1);
            }
            showDictionary(chatId);
        } else if (text.equals(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            int totalPages = (int) Math.ceil((double) wordRepository.findByLevelAndLang(
                userOpt.get().getLevel(), userOpt.get().getTargetLanguage()).size() / 30.0);
            if (currentPage < totalPages - 1) {
                userDictionaryPage.put(chatId, currentPage + 1);
            }
            showDictionary(chatId);
        } else if (text.equals(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单")) {
            userStates.put(chatId, ConversationState.IN_MENU);
            showMainMenu(chatId);
        } else {
            showDictionary(chatId);
        }
    }

    private void sendDictionaryPaginationKeyboard(Long chatId, int currentPage, int totalPages) {
        String nativeLang = userRepository.findByChatId(chatId).map(User::getNativeLanguage).orElse("ru");

        List<List<String>> buttons = new ArrayList<>();

        List<String> navRow = new ArrayList<>();
        if (currentPage > 0) {
            navRow.add(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
        }
        if (currentPage < totalPages - 1) {
            navRow.add(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
        }
        if (!navRow.isEmpty()) {
            buttons.add(navRow);
        }

        buttons.add(List.of(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单"));

        sendMessageWithButtons(chatId, " ", buttons);
    }

    private void showMyWordsMenu(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String menuText;
        List<List<String>> menuButtons;

        if (nativeLang.equals("ru")) {
            menuText = "📚 *Мои слова*";
            menuButtons = List.of(
                    List.of("❌ Не знаю", "⭐ Избранное"),
                    List.of("⬅️ Назад в меню")
            );
        } else {
            menuText = "📚 *我的单词*";
            menuButtons = List.of(
                    List.of("❌ 不认识", "⭐ 收藏"),
                    List.of("⬅️ 返回菜单")
            );
        }

        sendMessageWithButtons(chatId, menuText, menuButtons);
        userStates.put(chatId, ConversationState.IN_MY_WORDS);
    }

    private InlineKeyboardMarkup createFavoriteWordsInlineKeyboard(Long chatId, int currentPage, int totalPages, String nativeLang, List<UserFavoriteWord> wordsOnPage) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        int wordsPerRow = 5;
        for (int i = 0; i < wordsOnPage.size(); i += wordsPerRow) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = i; j < Math.min(i + wordsPerRow, wordsOnPage.size()); j++) {
                InlineKeyboardButton deleteButton = new InlineKeyboardButton();
                deleteButton.setText("❌ " + (currentPage * 30 + j + 1));
                deleteButton.setCallbackData("delete_favorite:" + wordsOnPage.get(j).getWord().getId());
                row.add(deleteButton);
            }
            rows.add(row);
        }

        if (!wordsOnPage.isEmpty()) {
            List<InlineKeyboardButton> deleteAllRow = new ArrayList<>();
            InlineKeyboardButton deleteAllButton = new InlineKeyboardButton();
            deleteAllButton.setText(nativeLang.equals("ru") ? "🗑️ Удалить все" : "🗑️ 删除所有");
            deleteAllButton.setCallbackData("delete_all_favorites");
            deleteAllRow.add(deleteAllButton);
            rows.add(deleteAllRow);
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (currentPage > 0) {
            InlineKeyboardButton backButton = new InlineKeyboardButton();
            backButton.setText(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
            backButton.setCallbackData("mywords_prev:" + (currentPage - 1) + ":favorites");
            navRow.add(backButton);
        }
        if (currentPage < totalPages - 1) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
            nextButton.setCallbackData("mywords_next:" + (currentPage + 1) + ":favorites");
            navRow.add(nextButton);
        }

        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        List<InlineKeyboardButton> sectionRow = new ArrayList<>();
        InlineKeyboardButton unknownButton = new InlineKeyboardButton();
        unknownButton.setText(nativeLang.equals("ru") ? "❌ Не знаю" : "❌ 不认识");
        unknownButton.setCallbackData("mywords_section:unknown");
        sectionRow.add(unknownButton);

        InlineKeyboardButton favoritesButton = new InlineKeyboardButton();
        favoritesButton.setText(nativeLang.equals("ru") ? "⭐ Избранное" : "⭐ 收藏");
        favoritesButton.setCallbackData("mywords_section:favorites");
        sectionRow.add(favoritesButton);
        rows.add(sectionRow);

        List<InlineKeyboardButton> menuRow = new ArrayList<>();
        InlineKeyboardButton menuButton = new InlineKeyboardButton();
        menuButton.setText(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单");
        menuButton.setCallbackData("main_menu");
        menuRow.add(menuButton);
        rows.add(menuRow);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup createMyWordsInlineKeyboard(Long chatId, int currentPage, int totalPages, String nativeLang, List<UserWord> wordsOnPage) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        int wordsPerRow = 5;
        for (int i = 0; i < wordsOnPage.size(); i += wordsPerRow) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = i; j < Math.min(i + wordsPerRow, wordsOnPage.size()); j++) {
                InlineKeyboardButton deleteButton = new InlineKeyboardButton();
                deleteButton.setText("❌ " + (currentPage * 30 + j + 1));
                deleteButton.setCallbackData("delete_unknown:" + wordsOnPage.get(j).getWord().getId());
                row.add(deleteButton);
            }
            rows.add(row);
        }

        if (!wordsOnPage.isEmpty()) {
            List<InlineKeyboardButton> deleteAllRow = new ArrayList<>();
            InlineKeyboardButton deleteAllButton = new InlineKeyboardButton();
            deleteAllButton.setText(nativeLang.equals("ru") ? "🗑️ Удалить все" : "🗑️ 删除所有");
            deleteAllButton.setCallbackData("delete_all_unknown");
            deleteAllRow.add(deleteAllButton);
            rows.add(deleteAllRow);
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (currentPage > 0) {
            InlineKeyboardButton backButton = new InlineKeyboardButton();
            backButton.setText(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
            backButton.setCallbackData("mywords_prev:" + (currentPage - 1) + ":unknown");
            navRow.add(backButton);
        }
        if (currentPage < totalPages - 1) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
            nextButton.setCallbackData("mywords_next:" + (currentPage + 1) + ":unknown");
            navRow.add(nextButton);
        }

        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        List<InlineKeyboardButton> sectionRow = new ArrayList<>();
        InlineKeyboardButton unknownButton = new InlineKeyboardButton();
        unknownButton.setText(nativeLang.equals("ru") ? "❌ Не знаю" : "❌ 不认识");
        unknownButton.setCallbackData("mywords_section:unknown");
        sectionRow.add(unknownButton);

        InlineKeyboardButton favoritesButton = new InlineKeyboardButton();
        favoritesButton.setText(nativeLang.equals("ru") ? "⭐ Избранное" : "⭐ 收藏");
        favoritesButton.setCallbackData("mywords_section:favorites");
        sectionRow.add(favoritesButton);
        rows.add(sectionRow);

        List<InlineKeyboardButton> menuRow = new ArrayList<>();
        InlineKeyboardButton menuButton = new InlineKeyboardButton();
        menuButton.setText(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单");
        menuButton.setCallbackData("main_menu");
        menuRow.add(menuButton);
        rows.add(menuRow);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private void handleMyWordsCommand(Long chatId, String command) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String backButtonText = nativeLang.equals("ru") ? "⬅️ Назад в меню" : "⬅️ 返回菜单";
        String unknownWordsButton = nativeLang.equals("ru") ? "❌ Не знаю" : "❌ 不认识";
        String favoritesButton = nativeLang.equals("ru") ? "⭐ Избранное" : "⭐ 收藏";

        if (command.equals(backButtonText)) {
            userStates.put(chatId, ConversationState.IN_MENU);
            showMainMenu(chatId);
            userWordDeleteMap.remove(chatId);
            return;
        } else if (command.equals(unknownWordsButton)) {
            currentMyWordsSection.put(chatId, "unknown");
            userDictionaryPage.put(chatId, 0);
            showMyWords(chatId);
        } else if (command.equals(favoritesButton)) {
            currentMyWordsSection.put(chatId, "favorites");
            userDictionaryPage.put(chatId, 0);
            showFavoriteWords(chatId);
        } else {
            String instruction = nativeLang.equals("ru") ? "Для взаимодействия с 'Моими словами' используй кнопки." : "要与“我的单词”互动，请使用按钮。";
            sendMessage(chatId, instruction);
            showMyWordsMenu(chatId);
        }
    }

    private void showFavoriteWords(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        List<UserFavoriteWord> allUserFavorites = userFavoriteWordRepository.findByUserChatId(chatId);
        if (allUserFavorites.isEmpty()) {
            String nativeLang = userOpt.get().getNativeLanguage();
            String message = nativeLang.equals("ru") ? "⭐ В избранном пока нет слов." : "⭐ 收藏中还没有单词。";
            sendMessage(chatId, message);
            showMyWordsMenu(chatId);
            return;
        }

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allUserFavorites.size() / pageSize);

        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
            userDictionaryPage.put(chatId, currentPage);
        }
        if (currentPage < 0) {
            currentPage = 0;
            userDictionaryPage.put(chatId, currentPage);
        }

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allUserFavorites.size());
        List<UserFavoriteWord> wordsOnPage = allUserFavorites.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = userOpt.get().getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("⭐ *Избранное* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("Нажми на кнопку ❌ чтобы удалить слово из избранного\n\n");
        } else {
            sb.append("⭐ *收藏* (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
            sb.append("点击 ❌ 按钮从收藏中删除单词\n\n");
        }

        for (int i = 0; i < wordsOnPage.size(); i++) {
            UserFavoriteWord uw = wordsOnPage.get(i);
            String wordLine;
            if (uw.getWord().getTranscription() != null && !uw.getWord().getTranscription().isEmpty()) {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " (" + uw.getWord().getTranscription() + ") — " + uw.getWord().getTranslation();
            } else {
                wordLine = (currentPage * pageSize + i + 1) + ". " + uw.getWord().getWord() + " — " + uw.getWord().getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createFavoriteWordsInlineKeyboard(chatId, currentPage, totalPages, nativeLang, wordsOnPage);
        sendMessageWithInlineKeyboard(chatId, sb.toString(), keyboard);
    }
    
    private void handleDeleteWord(Long chatId, String buttonCommand) {
        Map<String, Long> deleteMap = userWordDeleteMap.get(chatId);
        if (deleteMap == null || !deleteMap.containsKey(buttonCommand)) {
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String errorMessage = nativeLang.equals("ru") ? "❌ Ошибка при удалении слова." : "❌ 删除单词时出错。";
            sendMessage(chatId, errorMessage);
            showMyWords(chatId);
            return;
        }

        Long wordIdToDelete = deleteMap.get(buttonCommand);
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            userWordDeleteMap.remove(chatId);
            return;
        }

        Optional<UserWord> userWordOpt = userWordRepository.findByUserChatIdAndWordId(chatId, wordIdToDelete);
        if (userWordOpt.isPresent()) {
            userWordRepository.delete(userWordOpt.get());
            Optional<Word> wordOpt = wordRepository.findById(wordIdToDelete);
            String wordStr = wordOpt.map(Word::getWord).orElse("слово");
            String nativeLang = userOpt.get().getNativeLanguage();
            String successMessage;
            if (nativeLang.equals("ru")) {
                successMessage = "✅ Слово *" + wordStr + "* удалено из твоего списка.";
            } else {
                successMessage = "✅ 单词 *" + wordStr + "* 已从你的列表中删除。";
            }
            sendMessage(chatId, successMessage);
        } else {
            String nativeLang = userOpt.get().getNativeLanguage();
            String notFoundMessage = nativeLang.equals("ru") ? "❌ Слово не найдено в твоем списке." : "❌ 你的列表中找不到该单词。";
            sendMessage(chatId, notFoundMessage);
        }

        showMyWords(chatId);
    }

    private void showSettings(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String nativeLang = user.getNativeLanguage();

            String settingsText;
            List<List<String>> settingsButtons;
            
            if ("ru".equals(nativeLang)) {
                settingsText = "⚙️ *Твои настройки:*\n" +
                            "Родной язык: " + ("ru".equals(user.getNativeLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "Изучаемый язык: " + ("ru".equals(user.getTargetLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "Уровень: " + user.getLevel() + "\n\n" +
                            "Хочешь изменить что-нибудь?";
                
                settingsButtons = List.of(
                    List.of("🔄 Изменить родной язык"),
                    List.of("🔄 Изменить изучаемый язык"),
                    List.of("🔄 Изменить уровень"),
                    List.of("⬅️ Назад в меню")
                );
            } else {
                settingsText = "⚙️ *你的设置:*\n" +
                            "母语: " + ("ru".equals(user.getNativeLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "学习语言: " + ("ru".equals(user.getTargetLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "级别: " + user.getLevel() + "\n\n" +
                            "想要改变什么吗？";
                
                settingsButtons = List.of(
                    List.of("🔄 改变母语"),
                    List.of("🔄 改变学习语言"),
                    List.of("🔄 改变级别"),
                    List.of("⬅️ 返回菜单")
                );
            }

            sendMessageWithButtons(chatId, settingsText, settingsButtons);
            userStates.put(chatId, ConversationState.IN_SETTINGS); 
            
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private enum ConversationState {
        START, AWAITING_NATIVE_LANG, AWAITING_TARGET_LANG, AWAITING_LEVEL,
        IN_MENU, IN_MY_WORDS, IN_SENTENCE_GAME, IN_SETTINGS, IN_DICTIONARY,
        AWAITING_NEW_NATIVE_LANG, AWAITING_NEW_TARGET_LANG, AWAITING_NEW_LEVEL
    }

    private void handleSettingsCommand(Long chatId, String command) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }
        
        User user = userOpt.get();
        String nativeLang = user.getNativeLanguage();

        String changeNativeCmdRu = "🔄 Изменить родной язык";
        String changeTargetCmdRu = "🔄 Изменить изучаемый язык";
        String changeLevelCmdRu = "🔄 Изменить уровень";
        String sentenceAmountCmdRu = "📝 Кол-во предложений";
        String backCmdRu = "⬅️ Назад в меню";
        
        String changeNativeCmdZh = "🔄 改变母语";
        String changeTargetCmdZh = "🔄 改变学习语言";
        String changeLevelCmdZh = "🔄 改变级别";
        String sentenceAmountCmdZh = "📝 句子数量";
        String backCmdZh = "⬅️ 返回菜单";

        if (command.equals(changeNativeCmdRu) || command.equals(changeNativeCmdZh)) { 
            String text = "ru".equals(nativeLang) ? "Выбери свой новый родной язык:" : "选择你的新母语：";
            List<List<String>> languageButtons = List.of(List.of("🇷🇺 Русский", "🇨🇳 中文"));
            sendMessageWithButtons(chatId, text, languageButtons);
            userStates.put(chatId, ConversationState.AWAITING_NEW_NATIVE_LANG);
        } else if (command.equals(changeTargetCmdRu) || command.equals(changeTargetCmdZh)) {
            handleNewTargetLanguageRequest(chatId); 
        } else if (command.equals(changeLevelCmdRu) || command.equals(changeLevelCmdZh)) {
            String text = "ru".equals(nativeLang) ? "Выбери новый уровень знаний:" : "选择你的新级别：";
            List<List<String>> levelButtons = List.of(
                List.of("A1", "A2"),
                List.of("B1", "B2"),
                List.of("C1", "C2")
            );
            sendMessageWithButtons(chatId, text, levelButtons);
            userStates.put(chatId, ConversationState.AWAITING_NEW_LEVEL);
        } else if (command.equals(sentenceAmountCmdRu) || command.equals(sentenceAmountCmdZh)) {
            showSentenceAmountOptions(chatId);
        } else if (command.equals(backCmdRu) || command.equals(backCmdZh)) {
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
        } else if (command.matches("\\d+")) {
            int amount = Integer.parseInt(command);
            handleSentenceAmountSelection(chatId, amount);
        } else {
            String errorMessage = "ru".equals(nativeLang) ? 
                "Неизвестная команда. Пожалуйста, используй меню настроек." : 
                "未知命令。请使用设置菜单。";
            sendMessage(chatId, errorMessage);
            showSettings(chatId);
        }
    }

    private void handleSentenceAmountSelection(Long chatId, int amount) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setSentenceGameAmount(amount);
            userRepository.save(user);

            String nativeLang = user.getNativeLanguage();
            String confirmationText = nativeLang.equals("ru") ? 
                "✅ Количество предложений в игре изменено на *" + amount + "*" :
                "✅ 游戏中的句子数量已更改为 *" + amount + "*";

            sendMessage(chatId, confirmationText);
            showSettings(chatId);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void showSentenceAmountOptions(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String text;
        List<List<String>> buttons;

        if (nativeLang.equals("ru")) {
            text = "📝 *Выбери количество предложений в игре:*";
            buttons = List.of(
                List.of("5", "10", "15"),
                List.of("20", "25", "30"),
                List.of("40"),
                List.of("⬅️ Назад в настройки")
            );
        } else {
            text = "📝 *选择游戏中的句子数量:*";
            buttons = List.of(
                List.of("5", "10", "15"),
                List.of("20", "25", "30"),
                List.of("40"),
                List.of("⬅️ 返回设置")
            );
        }

        sendMessageWithButtons(chatId, text, buttons);
    }

    private void handleNewTargetLanguageRequest(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String nativeLang = user.getNativeLanguage();
            String text = "ru".equals(nativeLang) ? "Выбери новый язык для изучения:" : "选择你要学习的新语言：";
            List<List<String>> targetLangButtons;
            if ("ru".equals(nativeLang)) {
                targetLangButtons = List.of(List.of("🇨🇳 中文"));
            } else {
                targetLangButtons = List.of(List.of("🇷🇺 Русский"));
            }
            sendMessageWithButtons(chatId, text, targetLangButtons);
            userStates.put(chatId, ConversationState.AWAITING_NEW_TARGET_LANG);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void deleteUnknownWord(Long chatId, Long wordId, Integer messageId) {
        try {
            Optional<UserWord> userWordOpt = userWordRepository.findByUserChatIdAndWordId(chatId, wordId);
            if (userWordOpt.isPresent()) {
                userWordRepository.delete(userWordOpt.get());
                
                Optional<User> userOpt = userRepository.findByChatId(chatId);
                String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
                String successMessage = nativeLang.equals("ru") ? 
                    "✅ Слово удалено из списка 'Не знаю'!" : 
                    "✅ 单词已从'不认识'列表中删除！";

                sendMessage(chatId, successMessage);

                editMessageWithMyWords(chatId, messageId);
            } else {
                String errorMessage = "Слово не найдено в вашем списке.";
                sendMessage(chatId, errorMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, "Ошибка при удалении слова.");
        }
    }

    private void deleteFavoriteWord(Long chatId, Long wordId, Integer messageId) {
        try {
            Optional<UserFavoriteWord> userFavoriteWordOpt = userFavoriteWordRepository.findByUserChatIdAndWordId(chatId, wordId);
            if (userFavoriteWordOpt.isPresent()) {
                userFavoriteWordRepository.delete(userFavoriteWordOpt.get());
                
                Optional<User> userOpt = userRepository.findByChatId(chatId);
                String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
                String successMessage = nativeLang.equals("ru") ? 
                    "✅ Слово удалено из избранного!" : 
                    "✅ 单词已从收藏中删除！";

                sendMessage(chatId, successMessage);
                
                editMessageWithFavoriteWords(chatId, messageId);
            } else {
                String errorMessage = "Слово не найдено в избранном.";
                sendMessage(chatId, errorMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, "Ошибка при удалении слова.");
        }
    }

    private void handleNewNativeLanguageSelection(Long chatId, String selectedLanguage) {
        String nativeLangCode;
        if (selectedLanguage.equals("🇷🇺 Русский")) {
            nativeLangCode = "ru";
        } else if (selectedLanguage.equals("🇨🇳 中文")) {
            nativeLangCode = "zh";
        } else {
            sendMessage(chatId, "Пожалуйста, выбери язык из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setNativeLanguage(nativeLangCode);
            userRepository.save(user);

            String confirmationText = "✅ Родной язык успешно изменён на *" +
                    ("ru".equals(nativeLangCode) ? "🇷🇺 Русский" : "🇨🇳 中文") + "*";
            sendMessage(chatId, confirmationText);

            String newTargetLang = "ru".equals(nativeLangCode) ? "zh" : "ru";
            user.setTargetLanguage(newTargetLang);
            userRepository.save(user);
            
            String autoChangeText = "🔄 Изучаемый язык автоматически изменён на *" +
                    ("ru".equals(newTargetLang) ? "🇷🇺 Русский" : "🇨🇳 中文") + "*";
            sendMessage(chatId, autoChangeText);
            
            showSettings(chatId);
            userStates.put(chatId, ConversationState.IN_SETTINGS);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void handleNewTargetLanguageSelection(Long chatId, String selectedLanguage) {
        String targetLangCode;
        if (selectedLanguage.equals("🇷🇺 Русский")) {
            targetLangCode = "ru";
        } else if (selectedLanguage.equals("🇨🇳 中文")) {
            targetLangCode = "zh";
        } else {
            sendMessage(chatId, "Пожалуйста, выбери язык из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setTargetLanguage(targetLangCode);
            userRepository.save(user);

            String confirmationText = "✅ Изучаемый язык успешно изменён на *" +
                    ("ru".equals(targetLangCode) ? "🇷🇺 Русский" : "🇨🇳 中文") + "*";
            sendMessage(chatId, confirmationText);
            showSettings(chatId);
            userStates.put(chatId, ConversationState.IN_SETTINGS);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void handleNewLevelSelection(Long chatId, String selectedLevel) {
        if (!List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(selectedLevel)) {
            sendMessage(chatId, "Пожалуйста, выбери уровень из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLevel(selectedLevel);
            userRepository.save(user);

            String confirmationText = "✅ Уровень знаний успешно изменён на *" + selectedLevel + "*";
            sendMessage(chatId, confirmationText);
            showSettings(chatId);
            userStates.put(chatId, ConversationState.IN_SETTINGS);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void addToMyWords(Long chatId, Word word) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            Optional<UserWord> existingUW = userWordRepository.findByUserChatIdAndWordId(chatId, word.getId());
            if (existingUW.isEmpty()) {
                UserWord uw = new UserWord();
                uw.setUserChatId(chatId);
                uw.setWord(word);
                userWordRepository.save(uw);
            }
        }
    }

    private static class FlashcardGameSession {
        private final Long userId;
        private final String gameType;
        private final List<Word> words;
        private int currentIndex;
        private int correctCount = 0;
        private int dontKnowCount = 0;
        private final long startTime;
        private final boolean useMyWordsOnly;
        private final String gameLevel;

        public FlashcardGameSession(Long userId, String gameType, List<Word> words, int currentIndex, boolean useMyWordsOnly, String gameLevel) {
            this.userId = userId;
            this.gameType = gameType;
            this.words = new ArrayList<>(words);
            this.currentIndex = currentIndex;
            this.startTime = System.currentTimeMillis();
            this.useMyWordsOnly = useMyWordsOnly;
            this.gameLevel = gameLevel;
        }

        public Long getUserId() { return userId; }
        public String getGameType() { return gameType; }
        public String getGameLevel() { return gameLevel; }
        public List<Word> getWords() { return words; }
        public int getCurrentIndex() { return currentIndex; }
        public void setCurrentIndex(int currentIndex) { this.currentIndex = currentIndex; }
        public int getCorrectCount() { return correctCount; }
        public void incrementCorrectCount() { this.correctCount++; }
        public int getDontKnowCount() { return dontKnowCount; }
        public void incrementDontKnowCount() { this.dontKnowCount++; }
        public long getStartTime() { return startTime; }
        public boolean isUseMyWordsOnly() { return useMyWordsOnly; }
    }

    private void deleteAllUnknownWords(Long chatId, Integer messageId) {
        List<UserWord> userWords = userWordRepository.findByUserChatId(chatId);
        userWordRepository.deleteAll(userWords);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
        String successMessage = nativeLang.equals("ru") ? 
            "✅ Все слова удалены из списка 'Не знаю'!" : 
            "✅ 所有单词已从'不认识'列表中删除！";

        sendMessage(chatId, successMessage);
        editMessageWithMyWords(chatId, messageId);
    }

    private void deleteAllFavoriteWords(Long chatId, Integer messageId) {
        List<UserFavoriteWord> userFavorites = userFavoriteWordRepository.findByUserChatId(chatId);
        userFavoriteWordRepository.deleteAll(userFavorites);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
        String successMessage = nativeLang.equals("ru") ? 
            "✅ Все слова удалены из избранного!" : 
            "✅ 所有单词已从收藏中删除！";

        sendMessage(chatId, successMessage);
        editMessageWithFavoriteWords(chatId, messageId);
    }

    private static class SentenceGameSession {
        private final Long userId;
        private final List<Sentence> sentences;
        private int currentRound;
        private int correctCount;
        private int incorrectCount;
        private final long startTime;

        public SentenceGameSession(Long userId, List<Sentence> sentences, int totalRounds, String targetLanguage) {
            this.userId = userId;

            Collections.shuffle(sentences);
            this.sentences = sentences.subList(0, Math.min(totalRounds, sentences.size()));
            this.currentRound = 0;
            this.correctCount = 0;
            this.incorrectCount = 0;
            this.startTime = System.currentTimeMillis();
        }

        public Long getUserId() { return userId; }
        public List<Sentence> getSentences() { return sentences; }
        public int getCurrentRound() { return currentRound; }
        public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
        public int getCorrectCount() { return correctCount; }
        public void incrementCorrectCount() { this.correctCount++; }
        public int getIncorrectCount() { return incorrectCount; }
        public void incrementIncorrectCount() { this.incorrectCount++; }
        public long getStartTime() { return startTime; }
        public boolean isFinished() { return currentRound >= sentences.size(); }
        
        public Sentence getCurrentSentence() { 
            return sentences.get(currentRound); 
        }
    }
}