SELECT
    Album.Title AS Name
FROM Album
         LEFT JOIN Artist ON Album.ArtistId = Artist.ArtistId
ORDER BY
    Artist.Name ASC,
    Album.Title ASC
LIMIT 5 OFFSET 3;

-- +--------------------------------------+
-- |Name                                  |
-- +--------------------------------------+
-- |Worlds                                |
-- |The World of Classical Favourites     |
-- |Sir Neville Marriner: A Celebration   |
-- |Fauré: Requiem, Ravel: Pavane & Others|
-- |Bach: Orchestral Suites Nos. 1 - 4    |
-- +--------------------------------------+
