SELECT
  Track.Name
FROM Track
LEFT JOIN Album ON Track.AlbumId = Album.AlbumId
LEFT JOIN Artist ON Album.ArtistId = Artist.ArtistId
ORDER BY
  Artist.Name ASC,
  Track.Name ASC
LIMIT 5;

-- +------------------+
-- |Name              |
-- +------------------+
-- |Bad Boy Boogie    |
-- |Breaking The Rules|
-- |C.O.D.            |
-- |Dog Eat Dog       |
-- |Evil Walks        |
-- +------------------+
