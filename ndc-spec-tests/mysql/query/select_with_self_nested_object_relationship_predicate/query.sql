SELECT
  `Track`.`Name` AS `track`,
  `Track`.`AlbumId`,
  JSON_OBJECT(
    'album', `Album`.`Title`,
    'Artist', JSON_OBJECT(
      'artist', `Artist`.`Name`,
      'ArtistId', `Artist`.`ArtistId`
    )
  ) AS `Album`
FROM `Track`
LEFT JOIN `Album` ON `Track`.`AlbumId` = `Album`.`AlbumId`
LEFT JOIN `Artist` ON `Album`.`ArtistId` = `Artist`.`ArtistId`
WHERE `Album`.`AlbumId` > `Artist`.`ArtistId`
ORDER BY `Track`.`TrackId` ASC
LIMIT 5;

-- +--------------------+-------+-----------------------------------------------------------------------------+
-- |track               |AlbumId|Album                                                                        |
-- +--------------------+-------+-----------------------------------------------------------------------------+
-- |Fast As a Shark     |3      |{"album": "Restless and Wild", "Artist": {"artist": "Accept", "ArtistId": 2}}|
-- |Restless and Wild   |3      |{"album": "Restless and Wild", "Artist": {"artist": "Accept", "ArtistId": 2}}|
-- |Princess of the Dawn|3      |{"album": "Restless and Wild", "Artist": {"artist": "Accept", "ArtistId": 2}}|
-- |Go Down             |4      |{"album": "Let There Be Rock", "Artist": {"artist": "AC/DC", "ArtistId": 1}} |
-- |Dog Eat Dog         |4      |{"album": "Let There Be Rock", "Artist": {"artist": "AC/DC", "ArtistId": 1}} |
-- +--------------------+-------+-----------------------------------------------------------------------------+
