SELECT JSON_OBJECT(
  'rows', COALESCE(
    (
      SELECT JSON_ARRAYAGG(
        JSON_OBJECT(
          'Name', Artist.Name,
          'Albums', JSON_OBJECT(
            'rows', COALESCE(
              (
                SELECT JSON_ARRAYAGG(
                  JSON_OBJECT(
                    'Title', Album.Title,
                    'Tracks', JSON_OBJECT(
                      'rows', JSON_ARRAY(),
                      'aggregates', COALESCE(
                        (
                          SELECT JSON_OBJECT(
                            'how_many_tracks', COUNT(*)
                          )
                          FROM Track
                          WHERE Track.AlbumId = Album.AlbumId
                        ),
                        JSON_OBJECT()
                      )
                    )
                  ) ORDER BY Album.AlbumId ASC
                )
                FROM Album WHERE Album.ArtistId = Artist.ArtistId
              ),
              JSON_ARRAY()
            ),
            'aggregates', COALESCE(
              (
                SELECT JSON_OBJECT(
                  'how_many_albums', COUNT(*)
                )
                FROM Album
                WHERE Album.ArtistId = Artist.ArtistId
              ),
              JSON_OBJECT()
            )
          )
        )
      )
      FROM (
        SELECT Artist.ArtistId, Artist.Name
        FROM Artist
        ORDER BY (
          SELECT COUNT(*)
          FROM Album
          JOIN Track ON Track.AlbumId = Album.AlbumId
          WHERE Album.ArtistId = Artist.ArtistId
        ) DESC, Artist.Name DESC
        LIMIT 1
      ) AS Artist
    ),
    JSON_ARRAY()
  )
);
----------------------------------------------------------------------

set @t = @@group_concat_max_len;
set @@group_concat_max_len = 4294967295;
select json_object('rows', coalesce(
  json_merge_preserve(
    '[]',
    concat(
      '[',
      group_concat(json_object(
        'Name', Artist.Name,
        'Albums', json_object(
          'rows', coalesce(
            (
              select json_merge_preserve(
                '[]',
                concat(
                  '[',
                  group_concat(json_object(
                    'Title', Album.Title,
                    'Tracks', json_object('aggregates', coalesce(
                      (
                        select json_object('how_many_tracks', count(*))
                        from Track
                        where Track.AlbumId = Album.AlbumId
                      ),
                      json_object()
                    ))
                  ) order by Album.AlbumId asc separator ','),
                  ']'
                )
              )
              from Album
              where Album.ArtistId = Artist.ArtistId
            ),
            json_array()
          ),
          'aggregates', coalesce(
            (
              select json_object('how_many_albums', count(*))
              from Album
              where Album.ArtistId = Artist.ArtistId
            ),
            json_object()
          )
        )
      ) separator ','),
      ']'
    )
  ),
  json_array()
))
from (
  select Artist.ArtistId, Artist.Name
  from Artist
  order by
    (
      select count(*)
      from Album
        join Track
          on Track.AlbumId = Album.AlbumId
      where Album.ArtistId = Artist.ArtistId
    ) desc,
    Artist.Name desc
  limit 1
) as Artist;
set @@group_concat_max_len = @t;