import type { Dispatch, SetStateAction } from 'react'
import { useState, useCallback, useRef } from 'react'

type GetStateAction<S> = () => S

function useGetState<S>(
  initialState: S | (() => S),
): [S, Dispatch<SetStateAction<S>>, GetStateAction<S>]
function useGetState<S = undefined>(): [
  S | undefined,
  Dispatch<SetStateAction<S | undefined>>,
  GetStateAction<S | undefined>,
]
function useGetState<S>(initialState?: S) {
  const [state, setState] = useState(initialState)
  const stateRef = useRef(state)

  const setFlagState = useCallback((newState: S) => {
    stateRef.current = newState
    setState(newState)
  }, [])

  const getState = useCallback(() => stateRef.current, [])

  return [state, setFlagState, getState]
}

export default useGetState
