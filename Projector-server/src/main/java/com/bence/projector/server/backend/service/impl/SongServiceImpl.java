package com.bence.projector.server.backend.service.impl;

import com.bence.projector.common.model.SectionType;
import com.bence.projector.server.backend.model.FavouriteSong;
import com.bence.projector.server.backend.model.Language;
import com.bence.projector.server.backend.model.Song;
import com.bence.projector.server.backend.model.SongVerse;
import com.bence.projector.server.backend.model.SongVerseOrderListItem;
import com.bence.projector.server.backend.model.User;
import com.bence.projector.server.backend.repository.SongRepository;
import com.bence.projector.server.backend.repository.SongVerseOrderListItemRepository;
import com.bence.projector.server.backend.service.FavouriteSongService;
import com.bence.projector.server.backend.service.LanguageService;
import com.bence.projector.server.backend.service.ServiceException;
import com.bence.projector.server.backend.service.SongService;
import com.bence.projector.server.backend.service.SongVerseOrderListItemService;
import com.bence.projector.server.backend.service.SongVerseService;
import com.bence.projector.server.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.bence.projector.server.backend.service.util.QueryUtil.getStatement;
import static com.bence.projector.server.utils.ListUtil.twoListMatches;
import static com.bence.projector.server.utils.StringUtils.longestCommonSubString;

@Service
public class SongServiceImpl extends BaseServiceImpl<Song> implements SongService {

    private final String wordsSplit = "[.,;?_\"'\\n!:/|\\\\ ]";
    @Autowired
    private SongRepository songRepository;
    @Autowired
    private LanguageService languageService;
    @Autowired
    private SongVerseService songVerseService;
    @Autowired
    private SongVerseOrderListItemRepository songVerseOrderListItemRepository;
    private ConcurrentHashMap<String, Song> songsHashMap;
    private long lastModifiedDateTime = 0;
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Song>> songsHashMapByLanguage;
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> wordsHashMapByLanguage;
    @Autowired
    private SongVerseOrderListItemService songVerseOrderListItemService;
    @Autowired
    private FavouriteSongService favouriteSongService;

    private static boolean containsFavourite(List<FavouriteSong> favouriteSongs) {
        for (FavouriteSong favouriteSong : favouriteSongs) {
            if (favouriteSong.isFavourite()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isLanguageIsGood(Song song, Language language) {
        double x = getLanguagePercentage(song, language);
        return x > 0.7;
    }

    private double getLanguagePercentage(Song song, Language language) {
        ConcurrentHashMap<String, Boolean> wordsHashMap = getWordsHashMap(language);
        String text = getText(song);
        String[] split = text.split(wordsSplit);
        int count = 0;
        for (String s : split) {
            if (wordsHashMap.containsKey(s)) {
                ++count;
            }
        }
        int totalWordCount = split.length;
        double x = count;
        x /= totalWordCount;
        return x;
    }

    @Override
    public Language bestLanguage(Song song, List<Language> languages) {
        List<Language> localLanguages = new ArrayList<>(languages.size());
        for (Language language : languages) {
            language.setPercentage(getLanguagePercentage(song, language));
            localLanguages.add(language);
        }
        localLanguages.sort((o1, o2) -> Double.compare(o2.getPercentage(), o1.getPercentage()));
        return localLanguages.get(0);
    }

    private ConcurrentHashMap<String, Boolean> getWordsHashMap(Language language) {
        ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> wordsHashMapByLanguage = getWordsHashMapByLanguage();
        ConcurrentHashMap<String, Boolean> wordsHashMap = wordsHashMapByLanguage.get(language.getUuid());
        if (wordsHashMap == null) {
            wordsHashMap = new ConcurrentHashMap<>(10000);
            List<Song> allByLanguage = findAllByLanguage(language.getUuid());
            for (Song song : allByLanguage) {
                String text = getText(song);
                String[] split = text.split(wordsSplit);
                for (String s : split) {
                    wordsHashMap.put(s, true);
                }
            }
            wordsHashMapByLanguage.put(language.getUuid(), wordsHashMap);
        }
        return wordsHashMap;
    }

    @Override
    public List<Song> findAllAfterModifiedDate(Date lastModifiedDate) {
        final List<Song> songs = new ArrayList<>();
        List<Song> allByModifiedDateGreaterThan;
        if (lastModifiedDate.getTime() < 1000) {
            allByModifiedDateGreaterThan = findAllSongsLazy();
        } else {
            allByModifiedDateGreaterThan = getAllServiceSongs(songRepository.findAllByModifiedDateGreaterThan(lastModifiedDate));
        }
        addAfterModifiedDateSongs(lastModifiedDate, allByModifiedDateGreaterThan, songs);
        return songs;
    }

    private List<Song> getAllServiceSongs(List<Song> songs) {
        List<Song> songList = new ArrayList<>(songs.size());
        for (Song song : songs) {
            songList.add(getFromMapOrAddToMap(song));
        }
        return songList;
    }

    private void addAfterModifiedDateSongs(Date lastModifiedDate, List<Song> songs, List<Song> all) {
        for (Song song : songs) {
            if (!song.isDeleted() || song.getCreatedDate().compareTo(lastModifiedDate) <= 0) {
                all.add(song);
            }
        }
    }

    private void addSongs(List<Song> songs, List<Song> all) {
        for (Song song : songs) {
            if (!song.isDeleted()) {
                all.add(song);
            }
        }
    }

    @Override
    public List<Song> findAll() {
        final List<Song> songs = new ArrayList<>();
        List<Language> languages = languageService.findAll();
        for (Language language : languages) {
            addSongs(getAllServiceSongs(language.getSongs()), songs);
            language.setLanguageForSongs();
        }
        return songs;
    }

    @Override
    public List<Song> findAllByLanguage(String languageId) {
        final List<Song> songs = new ArrayList<>();
        Language language = languageService.findOneByUuid(languageId);
        addSongs(getAllServiceSongs(language.getSongs()), songs);
        language.setLanguageForSongs();
        return songs;
    }

    @Override
    public List<Song> findAllByLanguageAndModifiedDate(String languageId, Date lastModifiedDate) {
        List<Song> returningSongs = new ArrayList<>();
        Language language = languageService.findOneByUuid(languageId);
        List<Song> allByModifiedDateGreaterThanAndLanguage = songRepository.findAllByModifiedDateGreaterThanAndLanguage(lastModifiedDate, language);
        addSongs(allByModifiedDateGreaterThanAndLanguage, lastModifiedDate, returningSongs);
        return returningSongs;
    }

    @Override
    public List<Song> findAllByLanguageAndUser(String languageId, User user) {
        List<Song> returningSongs = new ArrayList<>();
        Language language = languageService.findOneByUuid(languageId);
        List<Song> songs = songRepository.findAllByLanguageAndCreatedByEmail(language, user.getEmail());
        addSongs(songs, returningSongs);
        return returningSongs;
    }

    @Override
    public List<Song> findAllByUploadedTrueAndDeletedTrueAndNotBackup() {
        List<Song> allByUploadedTrueAndDeletedTrue = new LinkedList<>();
        for (Song song : getSongs()) {
            if (song.isUploaded() && song.isDeleted() && !song.isBackUp()) {
                allByUploadedTrueAndDeletedTrue.add(song);
            }
        }
        return allByUploadedTrueAndDeletedTrue;
    }

    @Override
    public List<Song> findAllSimilar(Song song) {
        return findAllSimilar(song, false);
    }

    @Override
    public void deleteByUuid(String id) {
        Song oneByUuid = songRepository.findOneByUuid(id);
        if (oneByUuid == null) {
            return;
        }
        deleteNotFavouriteFavouriteSongs(oneByUuid);
        handleDeleteForBackup(oneByUuid);
        delete(oneByUuid.getId());
        ConcurrentHashMap<String, Song> songsHashMap = getSongsHashMap();
        if (songsHashMap != null) {
            if (songsHashMap.containsKey(id)) {
                Song song = songsHashMap.get(id);
                Language language = song.getLanguage();
                if (language != null) {
                    ConcurrentHashMap<String, Song> songsHashMapByLanguage = getSongsHashMapByLanguage(language);
                    songsHashMapByLanguage.remove(id);
                }
                songsHashMap.remove(id);
            }
        }
    }

    private void handleDeleteForBackup(Song song) {
        if (!song.isBackUp()) {
            return;
        }
        Song parentSong = songRepository.findByBackUp(song);
        if (parentSong == null) {
            return;
        }
        Song serviceParentSong = findOneByUuid(parentSong.getUuid());
        if (serviceParentSong != null) {
            parentSong = serviceParentSong;
        }
        parentSong.setBackUp(song.getBackUp());
        songRepository.save(parentSong);
    }

    private void deleteNotFavouriteFavouriteSongs(Song song) {
        try {
            List<FavouriteSong> favouriteSongs = song.getFavouriteSongs();
            if (!containsFavourite(favouriteSongs)) {
                favouriteSongService.deleteAll(favouriteSongs);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void delete(List<Long> ids) {
        for (Long id : ids) {
            delete(id);
        }
    }

    @Override
    public List<Song> findAllSimilar(Song song, boolean checkDeleted) {
        Collection<Song> songs = getSongsByLanguageForSimilar(song.getLanguage());
        if (songs == null) {
            return null;
        }
        return findAllSimilar(song, checkDeleted, songs);
    }

    @Override
    public List<Song> findAllSimilarSongsForSong(Song song, boolean checkDeleted, Collection<Song> songs) {
        // returns custom queried Song models
        String text = song.getTextLazyLowerCase();
        String songId = song.getUuid();
        HashMap<String, Boolean> wordHashMap = song.getWordHashMap();
        return getSimilarSongsForSong(checkDeleted, songs, text, songId, wordHashMap);
    }

    @Override
    public List<Song> findAllSimilar(Song song, boolean checkDeleted, Collection<Song> songs) {
        List<Song> similarSongsForSong = findAllSimilarSongsForSong(song, checkDeleted, songs);
        return getSongsFromRepository(similarSongsForSong);
    }

    private List<Song> getSongsFromRepository(List<Song> similarSongsForSong) {
        ArrayList<Song> songs = new ArrayList<>();
        for (Song song : similarSongsForSong) {
            Song songOptional = findOneByUuid(song.getUuid());
            if (songOptional != null) {
                songs.add(songOptional);
            }
        }
        return songs;
    }

    private List<Song> getSimilarSongsForSong(boolean checkDeleted, Collection<Song> songs, String text, String songUuid, HashMap<String, Boolean> wordHashMap) {
        List<Song> similar = new ArrayList<>();
        int wordCount = wordHashMap.keySet().size();
        for (Song databaseSong : songs) {
            try {
                if ((songUuid != null && databaseSong.getUuid().equals(songUuid)) || (databaseSong.isDeleted() && !checkDeleted)) {
                    continue;
                }
            } catch (NullPointerException e) {
                if (databaseSong != null) {
                    System.out.println(databaseSong.getTitle());
                }
                continue;
            }
            if (songsIsSimilar(text, wordHashMap, wordCount, databaseSong)) {
                similar.add(databaseSong);
            }
        }
        sortSimilar(similar);
        return similar;
    }

    private void sortSimilar(List<Song> similar) {
        similar.sort((o1, o2) -> Double.compare(o2.getSimilarRatio(), o1.getSimilarRatio()));
    }

    private boolean songsIsSimilar(String text, HashMap<String, Boolean> wordHashMap, int wordCount, Song databaseSong) {
        String secondText = databaseSong.getTextLazyLowerCase();
        int count = 0;
        Set<String> wordsSet = databaseSong.getWordHashMapKeySet();
        for (String word : wordsSet) {
            if (wordHashMap.containsKey(word)) {
                ++count;
            }
        }
        return isSimilarByCountAndSetPercentage(text, wordCount, databaseSong, secondText, count);
    }

    private boolean isSimilarByCountAndSetPercentage(String text, int wordCount, Song databaseSong, String secondText, int count) {
        double x = count;
        x /= wordCount;
        if (x > 0.5) {
            int highestCommonStringInt = StringUtils.highestCommonStringInt(text, secondText);
            x = highestCommonStringInt;
            x = x / text.length();
            if (x > 0.55) {
                double y;
                y = highestCommonStringInt;
                y = y / secondText.length();
                if (y > 0.55) {
                    x = (x + y) / 2;
                    databaseSong.setSimilarRatio(x);
                    return true;
                }
            }
            int longestCommonSubStringLength = longestCommonSubString(text, secondText);
            if (longestCommonSubStringLength > 50) {
                setRatio(text, databaseSong, secondText, longestCommonSubStringLength);
                return true;
            }
        }
        return false;
    }

    private void setRatio(String text, Song databaseSong, String secondText, int longestCommonSubStringLength) {
        double ratio = (double) longestCommonSubStringLength / Math.min(text.length(), secondText.length());
        databaseSong.setSimilarRatio(ratio);
    }

    @Override
    public void enrollSongInMap(Song song) {
        Language language = song.getLanguage();
        if (language == null) {
            return;
        }
        ConcurrentHashMap<String, Boolean> wordsHashMap = getWordsHashMap(language);
        String text = getText(song);
        String[] split = text.split(wordsSplit);
        for (String s : split) {
            wordsHashMap.put(s, true);
        }
    }

    @Override
    public List<Song> findAllInReviewByLanguage(Language language) {
        return getSongsWithReviewerErasedFalse(songRepository.findAllByLanguageAndUploadedIsTrueAndIsBackUpIsNullAndDeletedIsTrue(language));
    }

    private List<Song> getSongsWithReviewerErasedFalse(List<Song> songs) {
        List<Song> filtered = new ArrayList<>();
        for (Song song : songs) {
            if (!song.isReviewerErased() && song.getOriginalId() == null) {
                filtered.add(song);
            }
        }
        return filtered;
    }

    @Override
    public List<Song> findAllReviewedByUser(User user) {
        List<Song> songs = new ArrayList<>();
        for (Language languageNotInMap : user.getReviewLanguages()) {
            Language language = languageService.findOneByUuid(languageNotInMap.getUuid());
            for (Song song : getAllServiceSongsByLanguage(language)) {
                User lastModifiedBy = song.getLastModifiedBy();
                if (lastModifiedBy != null && lastModifiedBy.isSameId(user) && song.isPublic()) {
                    songs.add(song);
                }
            }
        }
        return songs;
    }

    private List<Song> getAllServiceSongsByLanguage(Language language) {
        return getAllServiceSongs(language.getSongs());
    }

    private ConcurrentHashMap<String, Song> getSongsHashMap() {
        if (songsHashMap == null) {
            songsHashMap = new ConcurrentHashMap<>(23221);
        }
        return songsHashMap;
    }

    private Collection<Song> getSongs() {
        ConcurrentHashMap<String, Song> songsHashMap = getSongsHashMap();
        if (songsHashMap.isEmpty()) {
            lastModifiedDateTime = 0;
            for (Song song : songRepository.findAll()) {
                putInMapAndCheckLastModifiedDate(song);
            }
        } else {
            for (Song song : songRepository.findAllByModifiedDateGreaterThan(new Date(lastModifiedDateTime))) {
                if (!songsHashMap.containsKey(song.getUuid())) {
                    putInMapAndCheckLastModifiedDate(song);
                } else {
                    checkLastModifiedDate(song);
                }
            }
        }
        return songsHashMap.values();
    }

    @Override
    public Collection<Song> getSongsByLanguageForSimilar(Language language) {
        return getAllByLanguageAndIsBackUpIsNullAndDeletedIsFalseAndReviewerErasedIsNull(language);
    }

    @Override
    public Collection<Song> getSongsByLanguageForSimilarWithVersionGroup(Language language) {
        return getAllByLanguageAndIsBackUpIsNullAndDeletedIsFalseAndReviewerErasedIsNullWithVersionGroup(language);
    }

    private List<Song> getAllByLanguageAndIsBackUpIsNullAndDeletedIsFalseAndReviewerErasedIsNull(Language language) {
        try {
            List<Song> songsFromResultSet = getSongsByLanguageFromResultSet(language);
            setVerseOrderListForSongsFromResultSet(songsFromResultSet, language);
            return songsFromResultSet;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private List<Song> getAllByLanguageAndIsBackUpIsNullAndDeletedIsFalseAndReviewerErasedIsNullWithVersionGroup(Language language) {
        try {
            List<Song> songsFromResultSet = getSongsByLanguageFromResultSetWithVersionGroup(language);
            setVerseOrderListForSongsFromResultSet(songsFromResultSet, language);
            return songsFromResultSet;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void setVerseOrderListForSongsFromResultSet(List<Song> songs, Language language) throws SQLException {
        if (songs.size() <= 0) {
            return;
        }
        ResultSet resultSet = getResultSet2(language);
        int index = 0;
        Song lastSong = null;
        Long lastSongId = null;
        List<SongVerseOrderListItem> songVerseOrderListItems = null;
        while (resultSet.next()) {
            long song_id = resultSet.getLong("song_id");
            if (lastSongId == null || lastSongId != song_id) {
                while (index < songs.size()) {
                    lastSong = songs.get(index++);
                    if (lastSong.getId().equals(song_id)) {
                        break;
                    }
                }
                if (lastSong != null) {
                    lastSongId = song_id;
                    songVerseOrderListItems = new ArrayList<>();
                    lastSong.setSongVerseOrderListItems(songVerseOrderListItems);
                }
            }
            if (songVerseOrderListItems != null) {
                SongVerseOrderListItem songVerseOrderListItem = new SongVerseOrderListItem();
                songVerseOrderListItems.add(songVerseOrderListItem);
                songVerseOrderListItem.setPosition(resultSet.getShort("position"));
            }
        }
    }

    private List<Song> getSongsByLanguageFromResultSet(Language language) throws SQLException {
        ResultSet resultSet = getResultSet(language);
        return getSongsFromResultSet(resultSet, false);
    }

    private List<Song> getSongsByLanguageFromResultSetWithVersionGroup(Language language) throws SQLException {
        ResultSet resultSet = getResultSetWithVersionGroup(language);
        return getSongsFromResultSet(resultSet, true);
    }

    private ResultSet getResultSet(Language language) throws SQLException {
        Statement statement = getStatement();
        String sql = getSelectSongFields() + getFromSong();
        return getSongJoinSongVersesResultSet(language, sql, statement);
    }

    private ResultSet getSongJoinSongVersesResultSet(Language language, String sql, Statement statement) throws SQLException {
        sql += " join song_verse on (song.id = song_verse.song_id)";
        sql = getConditionSqlByLanguage(language, sql);
        return statement.executeQuery(sql);
    }

    private ResultSet getResultSetWithVersionGroup(Language language) throws SQLException {
        Statement statement = getStatement();
        String sql = getSelectSongFields() + ", version_group_id" + getFromSong();
        return getSongJoinSongVersesResultSet(language, sql, statement);
    }

    private static String getFromSong() {
        return " from song";
    }

    private static String getSelectSongFields() {
        return "select uuid, title, deleted, is_back_up, reviewer_erased, text, section_type, song_id";
    }

    private String getConditionSqlByLanguage(Language language, String sql) {
        sql += " where deleted = 0";
        if (language != null) {
            sql += " and language_id = " + language.getId();
        }
        sql += " and is_back_up is null";
        sql += " and ((reviewer_erased is null) or (reviewer_erased = 0))";
        return sql;
    }

    private ResultSet getResultSet2(Language language) throws SQLException {
        Statement statement = getStatement();
        String sql = "select song_id, position from song_verse_order_list_item";
        sql += " join song on (song.id = song_verse_order_list_item.song_id)";
        sql = getConditionSqlByLanguage(language, sql);
        return statement.executeQuery(sql);
    }

    private List<Song> getSongsFromResultSet(ResultSet resultSet, boolean withVersionGroup) throws SQLException {
        List<Song> songs = new ArrayList<>();
        Long lastSongId = null;
        Song lastSong;
        ArrayList<SongVerse> songVerses = new ArrayList<>();
        while (resultSet.next()) {
            long song_id = resultSet.getLong("song_id");
            if (lastSongId == null || lastSongId != song_id) {
                lastSongId = song_id;
                Song song = new Song();
                songs.add(song);
                song.setId(song_id);
                song.setUuid(resultSet.getString("uuid"));
                song.setTitle(resultSet.getString("title"));
                song.setDeleted(resultSet.getBoolean("deleted"));
                song.setIsBackUp(getBooleanFromResultSet(resultSet, "is_back_up"));
                song.setReviewerErased(getBooleanFromResultSet(resultSet, "reviewer_erased"));
                if (withVersionGroup) {
                    song.setVersionGroupUuid(resultSet.getString("version_group_id"));
                }
                lastSong = song;
                songVerses = new ArrayList<>();
                lastSong.setVerses(songVerses);
            }
            SongVerse songVerse = new SongVerse();
            songVerses.add(songVerse);
            songVerse.setText(resultSet.getString("text"));
            songVerse.setSectionType(SectionType.getInstance(resultSet.getInt("section_type")));
        }
        return songs;
    }

    private Boolean getBooleanFromResultSet(ResultSet resultSet, String columnName) throws SQLException {
        boolean b = resultSet.getBoolean(columnName);
        if (!resultSet.wasNull()) {
            return b;
        }
        return null;
    }

    private void checkLastModifiedDate(Song song) {
        Date modifiedDate = song.getModifiedDate();
        if (modifiedDate == null) {
            return;
        }
        long time = modifiedDate.getTime();
        if (time > lastModifiedDateTime) {
            lastModifiedDateTime = time;
        }
    }

    private void putInMapAndCheckLastModifiedDate(Song song) {
        checkLastModifiedDate(song);
        ConcurrentHashMap<String, Song> songsHashMap = getSongsHashMap();
        songsHashMap.put(song.getUuid(), song);
    }

    @Override
    public boolean matches(Song song, Song song2) {
        if (!song.getTitle().equals(song2.getTitle())) {
            return false;
        }
        List<SongVerse> songVerses = song.getVerses();
        List<SongVerse> song2Verses = song2.getVerses();
        return twoListMatches(songVerses, song2Verses);
    }

    @Override
    public List<Song> findAllByVersionGroup(String versionGroupUuid) {
        Song versionGroupSong = songRepository.findOneByUuid(versionGroupUuid);
        ArrayList<Song> songs = new ArrayList<>();
        if (versionGroupSong == null) {
            return songs;
        }
        List<Song> allByVersionGroup = getAllServiceSongs(songRepository.findAllByVersionGroup(versionGroupSong));
        for (Song song : allByVersionGroup) {
            if (!song.isDeleted() && !song.isBackUp()) {
                songs.add(song);
            }
        }
        if (!versionGroupSong.isDeleted()) {
            String groupUuid = versionGroupSong.getVersionGroupUuid();
            if (groupUuid == null || !groupUuid.equals(versionGroupUuid)) {
                songs.add(versionGroupSong);
            }
        }
        return songs;
    }

    @Override
    public Song getRandomSong(Language language) {
        Random random = new Random();
        int size = (int) songRepository.countByLanguage(language);
        int n = random.nextInt(size);
        PageRequest pageRequest = PageRequest.of(n, 1);
        List<Song> songs = songRepository.findAllByLanguage(language, pageRequest);
        if (!songs.isEmpty()) {
            return songs.get(0);
        }
        return null;
    }

    @SuppressWarnings("Duplicates")
    private String getText(Song song) {
        try {
            final List<SongVerse> verses = song.getVerses();
            int size = verses.size();
            ArrayList<SongVerse> verseList = new ArrayList<>(size);
            List<Short> verseOrderList = song.getVerseOrderList();
            if (verseOrderList == null) {
                SongVerse chorus = null;
                for (int i = 0; i < size; ++i) {
                    SongVerse songVerse = verses.get(i);
                    verseList.add(songVerse);
                    if (songVerse.isChorus()) {
                        chorus = songVerse;
                    } else if (chorus != null) {
                        if (i + 1 < size) {
                            if (!verses.get(i + 1).isChorus()) {
                                verseList.add(chorus);
                            }
                        } else {
                            verseList.add(chorus);
                        }
                    }
                }
            } else {
                for (Short i : verseOrderList) {
                    if (i < verses.size()) {
                        verseList.add(verses.get(i));
                    }
                }
            }
            StringBuilder text = new StringBuilder();
            for (SongVerse songVerse : verseList) {
                text.append(songVerse.getText()).append(" ");
            }
            return text.toString();
        } catch (NullPointerException ignored) {
        }
        return "";
    }

    private void addSongs(List<Song> songs, Date lastModifiedDate, List<Song> returningSongs) {
        long lastModifiedDateTime = lastModifiedDate.getTime();
        for (Song song : songs) {
            try {
                if ((!song.isDeleted() || song.getCreatedDate().compareTo(lastModifiedDate) < 0) && song.getModifiedDate().getTime() > lastModifiedDateTime) {
                    returningSongs.add(song);
                }
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Song findOne(Long id) {
        Song song = super.findOne(id);
        if (song == null) {
            return null;
        }
        return findOneByUuid(song.getUuid());
    }

    public Song findOneByUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        ConcurrentHashMap<String, Song> songsHashMap = getSongsHashMap();
        Song song;
        if (songsHashMap.containsKey(uuid)) {
            song = songsHashMap.get(uuid);
        } else {
            song = songRepository.findOneByUuid(uuid);
        }
        return song;
    }

    @Override
    public void startThreadFindForSong(String uuid) {
        if (uuid == null) {
            return;
        }
        ConcurrentHashMap<String, Song> songsHashMap = getSongsHashMap();
        new Thread(() -> {
            if (songsHashMap.containsKey(uuid)) {
                songsHashMap.put(uuid, songRepository.findOneByUuid(uuid));
            }
        }).start();
    }

    @Override
    public Song reloadSong(Song song) {
        String uuid = song.getUuid();
        if (songsHashMap != null) {
            songsHashMap.remove(uuid);
            startThreadFindForSong(uuid);
        }
        return findOneByUuid(uuid);
    }

    @Override
    public List<Song> filterSongsByCreatedEmail(List<Song> songs, String createdByEmail) {
        if (createdByEmail == null) {
            return songs;
        }
        List<Song> filtered = new ArrayList<>();
        for (Song song : songs) {
            if (createdByEmail.equals(song.getCreatedByEmail())) {
                filtered.add(song);
            }
        }
        return filtered;
    }

    private Song getFromMapOrAddToMap(Song song) {
        ConcurrentHashMap<String, Song> songsHashMap = getSongsHashMap();
        String id = song.getUuid();
        if (songsHashMap.containsKey(id)) {
            return songsHashMap.get(id);
        }
        return song;
    }

    @Override
    public Song save(Song song) {
        if (song.getTitle() == null || song.getTitle().trim().isEmpty()) {
            throw new ServiceException("No title", HttpStatus.PRECONDITION_FAILED);
        }
        List<SongVerse> songVerses = song.getVerses();
        if (songVerses == null || songVerses.isEmpty()) {
            throw new ServiceException("songVerses isEmpty!", HttpStatus.PRECONDITION_FAILED);
        }
        if (song.isDeleted() && song.getLanguage() == null) {
            return songRepository.save(song);
        }
        if (song.getLanguage() == null) {
            throw new ServiceException("No language", HttpStatus.PRECONDITION_FAILED);
        }
        try {
            List<SongVerse> verses = new ArrayList<>(songVerses);
            List<SongVerseOrderListItem> songVerseOrderListItems = getCopyOfSongVerseOrderListItems(song);
            songRepository.save(song);
            songVerseService.deleteBySong(song);
            songVerseService.save(verses);
            song.setVerses(verses);
            songVerseOrderListItemRepository.deleteBySong(song);
            songVerseOrderListItemService.saveAllByRepository(songVerseOrderListItems);
            song.setSongVerseOrderListItems(songVerseOrderListItems);
        } catch (Exception e) {
            removeSongFromHashMap(song);
            throw e;
        }
        return song;
    }

    @Override
    public void saveAllAndRemoveCache(List<Song> songs) {
        save(songs);
        removeSongsFromHashMap(songs);
    }

    private void removeSongsFromHashMap(List<Song> songs) {
        for (Song song : songs) {
            removeSongFromHashMap(song);
        }
    }

    private void removeSongFromHashMap(Song song) {
        if (songsHashMap == null) {
            return;
        }
        String uuid = song.getUuid();
        songsHashMap.remove(uuid);
    }

    private ArrayList<SongVerseOrderListItem> getCopyOfSongVerseOrderListItems(Song song) {
        List<SongVerseOrderListItem> songVerseOrderListItems = song.getSongVerseOrderListItems();
        if (songVerseOrderListItems == null) {
            return null;
        }
        return new ArrayList<>(songVerseOrderListItems);
    }

    @Override
    public List<Song> findAllContainingYoutubeUrl() {
        return getAllServiceSongs(songRepository.findAllByYoutubeUrlNotNull());
    }

    @Override
    public List<Song> findAllByLanguageContainingViews(String languageId) {
        Language language = languageService.findOneByUuid(languageId);
        List<Song> songs = new ArrayList<>(language.getSongs().size());
        for (Song song : language.getSongs()) {
            if (song.isDeleted() || song.getViews() == 0) {
                continue;
            }
            songs.add(song);
        }
        return songs;
    }

    @Override
    public List<Song> findAllByLanguageContainingFavourites(String languageId) {
        Language language = languageService.findOneByUuid(languageId);
        List<Song> songs = new ArrayList<>(language.getSongs().size());
        for (Song song : language.getSongs()) {
            if (song.isDeleted() || song.getFavourites() == 0) {
                continue;
            }
            songs.add(song);
        }
        return songs;
    }

    @Override
    public List<Song> findAllSongsLazy() {
        Collection<Song> songs = getSongs();
        List<Song> songList = new ArrayList<>(songs.size());
        songList.addAll(songs);
        return songList;
    }

    @Override
    public Iterable<Song> save(List<Song> songs) {
        for (Song song : songs) {
            save(song);
        }
        return songs;
    }

    private ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> getWordsHashMapByLanguage() {
        if (wordsHashMapByLanguage == null) {
            wordsHashMapByLanguage = new ConcurrentHashMap<>(20);
        }
        return wordsHashMapByLanguage;
    }

    private ConcurrentHashMap<String, ConcurrentHashMap<String, Song>> getSongsHashMapByLanguage() {
        if (songsHashMapByLanguage == null) {
            songsHashMapByLanguage = new ConcurrentHashMap<>(20);
        }
        return songsHashMapByLanguage;
    }

    private ConcurrentHashMap<String, Song> getSongsHashMapByLanguage(Language language) {
        ConcurrentHashMap<String, ConcurrentHashMap<String, Song>> songsHashMapByLanguage = getSongsHashMapByLanguage();
        String key = language.getUuid();
        if (!songsHashMapByLanguage.containsKey(key)) {
            int songsCount = Math.toIntExact(language.getSongsCount());
            ConcurrentHashMap<String, Song> songHashMap = new ConcurrentHashMap<>(songsCount);
            songsHashMapByLanguage.put(key, songHashMap);
        }
        return songsHashMapByLanguage.get(key);
    }

}
