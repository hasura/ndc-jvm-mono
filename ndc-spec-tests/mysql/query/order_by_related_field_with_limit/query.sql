SELECT
    `Album`.`Title` AS `Name`,
    JSON_OBJECT(
            'Name', `Artist`.`Name`
    ) AS `Artist`
FROM `Album`
         LEFT JOIN `Artist` ON `Album`.`ArtistId` = `Artist`.`ArtistId`
ORDER BY
    `Artist`.`Name` ASC,
    `Album`.`Title` ASC
    LIMIT 5;

-- +-------------------------------------+----------------------------------------------------------------------+
-- |Name                                 |Artist                                                                |
-- +-------------------------------------+----------------------------------------------------------------------+
-- |For Those About To Rock We Salute You|{"Name": "AC/DC"}                                                     |
-- |Let There Be Rock                    |{"Name": "AC/DC"}                                                     |
-- |A Copland Celebration, Vol. I        |{"Name": "Aaron Copland & London Symphony Orchestra"}                 |
-- |Worlds                               |{"Name": "Aaron Goldberg"}                                            |
-- |The World of Classical Favourites    |{"Name": "Academy of St. Martin in the Fields & Sir Neville Marriner"}|
-- +-------------------------------------+----------------------------------------------------------------------+
