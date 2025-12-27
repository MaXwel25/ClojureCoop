:- module(arena_bot, [bot_action/6]).

% Главная функция AI босса
bot_action(BotX, BotY, Players, _, _, Action) :-
    % Находим ближайшего живого игрока
    find_closest_player(BotX, BotY, Players, ClosestPlayer),
    (   ClosestPlayer = player(_, TargetX, TargetY, _, _, _)
    ->  calculate_distance(BotX, BotY, TargetX, TargetY, Distance),
        (   Distance < 100
        ->  calculate_shoot_vector(BotX, BotY, TargetX, TargetY, TX, TY),
            Action = shoot(TX, TY)
        ;   Distance < 400
        ->  calculate_move_vector(BotX, BotY, TargetX, TargetY, DX, DY),
            Action = move(DX, DY)
        ;   Action = wait
        )
    ;   Action = wait
    ).

% Найти ближайшего игрока (работает с пустым списком)
find_closest_player(_, _, [], none) :- !.
find_closest_player(BotX, BotY, Players, Closest) :-
    findall(Distance-Player, 
            (member(Player, Players), 
             player_distance(BotX, BotY, Player, Distance)),
            Distances),
    sort(Distances, Sorted),
    (   Sorted = [_-Closest|_]
    ->  true
    ;   Closest = none
    ).

% Вычислить расстояние до игрока
player_distance(BotX, BotY, player(_, PX, PY, _, _, _), Distance) :-
    calculate_distance(BotX, BotY, PX, PY, Distance).

% Вычислить расстояние между двумя точками
calculate_distance(X1, Y1, X2, Y2, Distance) :-
    DX is X2 - X1,
    DY is Y2 - Y1,
    Distance is sqrt(DX * DX + DY * DY).

% Вычислить вектор для стрельбы
calculate_shoot_vector(BotX, BotY, TargetX, TargetY, TX, TY) :-
    TX is TargetX - BotX,
    TY is TargetY - BotY.

% Вычислить вектор для движения (нормализованный)
calculate_move_vector(BotX, BotY, TargetX, TargetY, DX, DY) :-
    TX is TargetX - BotX,
    TY is TargetY - BotY,
    Length is sqrt(TX * TX + TY * TY),
    (Length > 0 ->
        DX is TX / Length,
        DY is TY / Length
    ;
        DX = 0, DY = 0
    ).