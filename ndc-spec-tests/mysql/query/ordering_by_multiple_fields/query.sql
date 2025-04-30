SELECT
  Album.Title
FROM Album
ORDER BY
  Album.Title ASC,
  Album.AlbumId DESC
LIMIT 5 OFFSET 3;

-- +------------------------------------------+
-- |Title                                     |
-- +------------------------------------------+
-- |A Matter of Life and Death                |
-- |A Real Dead One                           |
-- |A Real Live One                           |
-- |A Soprano Inspired                        |
-- |A TempestadeTempestade Ou O Livro Dos Dias|
-- +------------------------------------------+
